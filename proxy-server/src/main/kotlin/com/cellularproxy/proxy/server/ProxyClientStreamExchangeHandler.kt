package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.ConnectTunnelBidirectionalRelay
import com.cellularproxy.proxy.forwarding.ConnectTunnelClientConnection
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeHandler
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandler
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnection
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.toProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflight
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiStreamAuditEvent
import com.cellularproxy.proxy.management.ManagementApiStreamAuditOutcome
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandler
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.proxy.protocol.TlsClientHelloInspectionResult
import com.cellularproxy.proxy.protocol.TlsClientHelloInspector
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.SocketTimeoutException

data class ProxyClientStreamConnection(
    val input: InputStream,
    val output: OutputStream,
    private val shutdownInputAction: () -> Unit = { input.close() },
    private val shutdownOutputAction: () -> Unit = { output.close() },
    private val setReadTimeoutMillisAction: (Int) -> Unit = {},
) : Closeable {
    fun toConnectTunnelClientConnection(): ConnectTunnelClientConnection = ConnectTunnelClientConnection(
        input = input,
        output = output,
        shutdownInputAction = shutdownInputAction,
        shutdownOutputAction = shutdownOutputAction,
    )

    fun clearReadTimeout() {
        setReadTimeoutMillisAction(NO_READ_TIMEOUT)
    }

    fun replacingInput(input: InputStream): ProxyClientStreamConnection = ProxyClientStreamConnection(
        input = input,
        output = output,
        shutdownInputAction = shutdownInputAction,
        shutdownOutputAction = shutdownOutputAction,
        setReadTimeoutMillisAction = setReadTimeoutMillisAction,
    )

    override fun close() {
        var failure: Throwable? = null

        try {
            input.close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        try {
            output.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run {
                failure = throwable
            }
        }

        failure?.let { throw it }
    }
}

sealed interface ProxyClientStreamExchangeHandlingResult {
    data class PreflightRejected(
        val failure: ProxyServerFailure,
        val responseBytesWritten: Int,
        val requiresAuditLog: Boolean,
        val headerBytesRead: Int,
    ) : ProxyClientStreamExchangeHandlingResult {
        init {
            require(responseBytesWritten >= 0) { "Response bytes written must be non-negative" }
            require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        }
    }

    data class HttpProxyHandled(
        val headerBytesRead: Int,
        val result: HttpProxyOutboundExchangeHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult {
        init {
            require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        }
    }

    data class ConnectTunnelHandled(
        val headerBytesRead: Int,
        val result: ConnectTunnelOutboundExchangeRelayHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult {
        init {
            require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        }
    }

    data class ManagementHandled(
        val headerBytesRead: Int,
        val result: ManagementApiStreamExchangeHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult {
        init {
            require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        }
    }

    data class TransparentTlsTunnelHandled(
        val initialBytesRead: Int,
        val result: ConnectTunnelOutboundExchangeRelayHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult {
        init {
            require(initialBytesRead > 0) { "Initial TLS bytes read must be positive" }
        }
    }
}

class ProxyClientStreamExchangeHandler(
    httpConnector: OutboundHttpOriginConnector,
    private val connectConnector: OutboundConnectTunnelConnector,
    managementHandler: ManagementApiHandler,
    private val proxyActivityTracker: ProxyTrafficActivityTracker = ProxyTrafficActivityTracker(),
    private val recordManagementAudit: (ManagementApiStreamAuditEvent) -> Unit = {},
) {
    private val httpHandler = HttpProxyOutboundExchangeHandler(httpConnector)
    private val connectHandler = ConnectTunnelOutboundExchangeHandler(connectConnector)
    private val managementHandler =
        ManagementApiStreamExchangeHandler(
            handler = managementHandler,
            recordManagementAudit = recordManagementAudit,
        )

    val activeProxyExchanges: Long
        get() = proxyActivityTracker.activeProxyExchanges

    fun handle(
        config: ProxyIngressPreflightConfig,
        activeConnections: Long,
        client: ProxyClientStreamConnection,
        httpBufferSize: Int = DEFAULT_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyClientStreamExchangeHandlingResult {
        try {
            require(httpBufferSize > 0) { "HTTP buffer size must be positive" }
            require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
            require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
            require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }
            require(connectRelayBufferSize > 0) { "CONNECT relay buffer size must be positive" }

            val inspectedClient =
                if (config.transparentTlsSniProxyEnabled()) {
                    val pushbackInput = PushbackInputStream(client.input, TRANSPARENT_TLS_PUSHBACK_BYTES)
                    when (val inspection = inspectTlsClientHelloOrFallback(pushbackInput)) {
                        is TlsClientHelloInspectionResult.ClientHello ->
                            return handleTransparentTls(
                                serverName = inspection.serverName,
                                initialClientBytes = inspection.bytes,
                                client = client.replacingInput(pushbackInput),
                                connectRelayBufferSize = connectRelayBufferSize,
                                recordMetricEvent = recordMetricEvent,
                            )
                        TlsClientHelloInspectionResult.NotTls,
                        TlsClientHelloInspectionResult.UnsupportedTls,
                        -> client.replacingInput(pushbackInput)
                    }
                } else {
                    client
                }

            val preflight =
                ProxyIngressStreamPreflight.evaluate(
                    config = config,
                    activeConnections = activeConnections,
                    input = inspectedClient.input,
                )

            return when (preflight) {
                is ProxyIngressStreamPreflightDecision.Accepted -> {
                    inspectedClient.clearReadTimeout()
                    ProxyClientStreamExchangeMetrics
                        .acceptedStartedEvents(preflight.headerBytesRead)
                        .recordSafely(recordMetricEvent)
                    val proxyActivity = proxyActivityTracker.begin(preflight.request)
                    var completed = false
                    try {
                        val result =
                            handleAccepted(
                                accepted = preflight,
                                client = inspectedClient,
                                httpBufferSize = httpBufferSize,
                                maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                                maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                                maxResponseTrailerBytes = maxResponseTrailerBytes,
                                connectRelayBufferSize = connectRelayBufferSize,
                            )
                        ProxyClientStreamExchangeMetrics
                            .acceptedCompletedEvents(result)
                            .recordSafely(recordMetricEvent)
                        completed = true
                        result
                    } finally {
                        proxyActivity.finish()
                        if (!completed) {
                            ProxyClientStreamExchangeMetrics.acceptedClosedEvent().recordSafely(recordMetricEvent)
                        }
                    }
                }
                is ProxyIngressStreamPreflightDecision.Rejected ->
                    run {
                        recordRejectedManagementAuditIfNeeded(preflight)
                        writePreflightRejection(preflight, inspectedClient.output)
                            .also { rejected ->
                                ProxyClientStreamExchangeMetrics
                                    .eventsFor(rejected)
                                    .recordSafely(recordMetricEvent)
                            }
                    }
            }
        } finally {
            client.closeAfterExchange()
        }
    }

    private fun ProxyClientStreamConnection.closeAfterExchange() {
        try {
            close()
        } catch (_: Exception) {
            // Client socket close failures after handling must not replace the exchange outcome.
        }
    }

    private fun inspectTlsClientHelloOrFallback(input: PushbackInputStream): TlsClientHelloInspectionResult = try {
        TlsClientHelloInspector.inspect(input)
    } catch (_: SocketTimeoutException) {
        TlsClientHelloInspectionResult.NotTls
    }

    private fun handleTransparentTls(
        serverName: String,
        initialClientBytes: ByteArray,
        client: ProxyClientStreamConnection,
        connectRelayBufferSize: Int,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit,
    ): ProxyClientStreamExchangeHandlingResult.TransparentTlsTunnelHandled {
        client.clearReadTimeout()
        val request = ParsedProxyRequest.ConnectTunnel(host = serverName, port = HTTPS_PORT)
        ProxyClientStreamExchangeMetrics
            .acceptedStartedEvents(initialClientBytes.size)
            .recordSafely(recordMetricEvent)
        val proxyActivity = proxyActivityTracker.begin(request)
        var completed = false
        return try {
            val result =
                when (val openResult = connectConnector.open(request)) {
                    is OutboundConnectTunnelOpenResult.Connected ->
                        openResult.connection.relayTransparentTls(
                            client = client.toConnectTunnelClientConnection(),
                            initialClientBytes = initialClientBytes,
                            relayBufferSize = connectRelayBufferSize,
                        )
                    is OutboundConnectTunnelOpenResult.Failed ->
                        ConnectTunnelOutboundExchangeRelayHandlingResult.ConnectionFailed(
                            failure = openResult.reason.toProxyServerFailure(),
                            errorResponseBytesWritten = 0,
                        )
                }
            val handled =
                ProxyClientStreamExchangeHandlingResult.TransparentTlsTunnelHandled(
                    initialBytesRead = initialClientBytes.size,
                    result = result,
                )
            ProxyClientStreamExchangeMetrics
                .acceptedCompletedEvents(handled)
                .recordSafely(recordMetricEvent)
            completed = true
            handled
        } finally {
            proxyActivity.finish()
            if (!completed) {
                ProxyClientStreamExchangeMetrics.acceptedClosedEvent().recordSafely(recordMetricEvent)
            }
        }
    }

    private fun handleAccepted(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        client: ProxyClientStreamConnection,
        httpBufferSize: Int,
        maxOriginResponseHeaderBytes: Int,
        maxResponseChunkHeaderBytes: Int,
        maxResponseTrailerBytes: Int,
        connectRelayBufferSize: Int,
    ): ProxyClientStreamExchangeHandlingResult = when (accepted.request) {
        is ParsedProxyRequest.HttpProxy ->
            ProxyClientStreamExchangeHandlingResult.HttpProxyHandled(
                headerBytesRead = accepted.headerBytesRead,
                httpHandler.handle(
                    accepted = accepted,
                    clientInput = client.input,
                    clientOutput = client.output,
                    bufferSize = httpBufferSize,
                    maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                    maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                    maxResponseTrailerBytes = maxResponseTrailerBytes,
                ),
            )
        is ParsedProxyRequest.ConnectTunnel ->
            ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled(
                headerBytesRead = accepted.headerBytesRead,
                connectHandler.handleAndRelay(
                    accepted = accepted,
                    client = client.toConnectTunnelClientConnection(),
                    relayBufferSize = connectRelayBufferSize,
                ),
            )
        is ParsedProxyRequest.Management ->
            ProxyClientStreamExchangeHandlingResult.ManagementHandled(
                headerBytesRead = accepted.headerBytesRead,
                managementHandler.handle(
                    accepted = accepted,
                    clientOutput = client.output,
                ),
            )
    }

    private fun writePreflightRejection(
        rejected: ProxyIngressStreamPreflightDecision.Rejected,
        clientOutput: OutputStream,
    ): ProxyClientStreamExchangeHandlingResult.PreflightRejected {
        val bytesWritten =
            when (val response = rejected.response) {
                is ProxyErrorResponseDecision.Emit -> {
                    val bytes = response.response.toByteArray()
                    clientOutput.write(bytes)
                    clientOutput.flush()
                    bytes.size
                }
                ProxyErrorResponseDecision.Suppress -> 0
            }

        return ProxyClientStreamExchangeHandlingResult.PreflightRejected(
            failure = rejected.failure,
            responseBytesWritten = bytesWritten,
            requiresAuditLog = rejected.requiresAuditLog,
            headerBytesRead = rejected.headerBytesRead,
        )
    }

    private fun recordRejectedManagementAuditIfNeeded(preflight: ProxyIngressStreamPreflightDecision.Rejected) {
        if (!preflight.requiresAuditLog) {
            return
        }
        val statusCode =
            when (val response = preflight.response) {
                is ProxyErrorResponseDecision.Emit -> response.response.statusCode
                ProxyErrorResponseDecision.Suppress -> return
            }
        recordManagementAuditSafely(
            ManagementApiStreamAuditEvent(
                operation = null,
                outcome = ManagementApiStreamAuditOutcome.AuthorizationRejected,
                statusCode = statusCode,
                disposition = null,
            ),
        )
    }

    private fun recordManagementAuditSafely(event: ManagementApiStreamAuditEvent) {
        try {
            recordManagementAudit(event)
        } catch (_: Exception) {
            // Audit sink failures must not replace proxy handling outcomes.
        }
    }
}

private fun ProxyIngressPreflightConfig.transparentTlsSniProxyEnabled(): Boolean = !proxyRequestsPaused && !requestAdmission.proxyAuthentication.authEnabled

private fun OutboundConnectTunnelConnection.relayTransparentTls(
    client: ConnectTunnelClientConnection,
    initialClientBytes: ByteArray,
    relayBufferSize: Int,
): ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed = try {
    output.write(initialClientBytes)
    output.flush()
    val relayResult =
        ConnectTunnelBidirectionalRelay.relay(
            client = client,
            origin = this,
            bufferSize = relayBufferSize,
        )
    ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed(
        host = host,
        port = port,
        responseBytesWritten = 0,
        relayResult = relayResult,
    )
} finally {
    closeAfterTransparentTlsExchange()
}

private fun OutboundConnectTunnelConnection.closeAfterTransparentTlsExchange() {
    try {
        close()
    } catch (_: Exception) {
        // Close failures after transparent TLS relay must not replace the exchange outcome.
    }
}

private const val DEFAULT_HTTP_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_ORIGIN_RESPONSE_HEADER_BYTES = 16 * 1024
private const val DEFAULT_RESPONSE_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_TRAILER_BYTES = 16 * 1024
private const val DEFAULT_CONNECT_RELAY_BUFFER_BYTES = 8 * 1024
private const val NO_READ_TIMEOUT = 0
private const val HTTPS_PORT = 443
private const val TRANSPARENT_TLS_PUSHBACK_BYTES = 16 * 1024 + 5

private fun List<ProxyTrafficMetricsEvent>.recordSafely(recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit) {
    forEach { event -> event.recordSafely(recordMetricEvent) }
}

private fun ProxyTrafficMetricsEvent.recordSafely(recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit) {
    try {
        recordMetricEvent(this)
    } catch (_: Exception) {
        // Metrics sinks are best-effort and must not interrupt proxy exchange handling.
    }
}
