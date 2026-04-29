package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

fun interface OutboundConnectTunnelConnector {
    fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult
}

data class OutboundConnectTunnelConnection(
    val input: InputStream,
    val output: OutputStream,
    val host: String,
    val port: Int,
    private val shutdownInputAction: () -> Unit = { input.close() },
    private val shutdownOutputAction: () -> Unit = { output.close() },
) : Closeable {
    init {
        require(host.isNotBlank()) { "Tunnel origin host must not be blank" }
        require(port in VALID_CONNECT_TUNNEL_PORT_RANGE) { "Tunnel origin port must be in range 1..65535" }
    }

    fun shutdownInput() {
        shutdownInputAction()
    }

    fun shutdownOutput() {
        shutdownOutputAction()
    }

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

sealed interface OutboundConnectTunnelOpenResult {
    data class Connected(
        val connection: OutboundConnectTunnelConnection,
    ) : OutboundConnectTunnelOpenResult

    data class Failed(
        val reason: OutboundConnectTunnelOpenFailure,
    ) : OutboundConnectTunnelOpenResult
}

enum class OutboundConnectTunnelOpenFailure {
    SelectedRouteUnavailable,
    DnsResolutionFailed,
    OutboundConnectionFailed,
    OutboundConnectionTimeout,
}

fun OutboundConnectTunnelOpenFailure.toProxyServerFailure(): ProxyServerFailure = when (this) {
    OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable -> ProxyServerFailure.SelectedRouteUnavailable
    OutboundConnectTunnelOpenFailure.DnsResolutionFailed -> ProxyServerFailure.DnsResolutionFailed
    OutboundConnectTunnelOpenFailure.OutboundConnectionFailed -> ProxyServerFailure.OutboundConnectionFailed
    OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout -> ProxyServerFailure.OutboundConnectionTimeout
}

sealed interface ConnectTunnelOutboundExchangeHandlingResult {
    data class Established(
        val connection: OutboundConnectTunnelConnection,
        val host: String,
        val port: Int,
        val responseBytesWritten: Int,
    ) : ConnectTunnelOutboundExchangeHandlingResult {
        init {
            require(host.isNotBlank()) { "Established tunnel host must not be blank" }
            require(port in VALID_CONNECT_TUNNEL_PORT_RANGE) { "Established tunnel port must be in range 1..65535" }
            require(responseBytesWritten >= 0) { "Response bytes written must be non-negative" }
        }
    }

    data class ConnectionFailed(
        val failure: ProxyServerFailure,
        val errorResponseBytesWritten: Int,
    ) : ConnectTunnelOutboundExchangeHandlingResult {
        init {
            require(
                failure == ProxyServerFailure.SelectedRouteUnavailable ||
                    failure == ProxyServerFailure.DnsResolutionFailed ||
                    failure == ProxyServerFailure.OutboundConnectionFailed ||
                    failure == ProxyServerFailure.OutboundConnectionTimeout,
            ) { "Connection failures must use an outbound connection failure category" }
            require(errorResponseBytesWritten >= 0) { "Error response bytes written must be non-negative" }
        }
    }

    data class UnsupportedAcceptedRequest(
        val reason: ConnectTunnelOutboundExchangeUnsupportedReason,
    ) : ConnectTunnelOutboundExchangeHandlingResult
}

sealed interface ConnectTunnelOutboundExchangeRelayHandlingResult {
    data class Relayed(
        val host: String,
        val port: Int,
        val responseBytesWritten: Int,
        val relayResult: ConnectTunnelBidirectionalRelayResult,
    ) : ConnectTunnelOutboundExchangeRelayHandlingResult {
        init {
            require(host.isNotBlank()) { "Relayed tunnel host must not be blank" }
            require(port in VALID_CONNECT_TUNNEL_PORT_RANGE) { "Relayed tunnel port must be in range 1..65535" }
            require(responseBytesWritten >= 0) { "Response bytes written must be non-negative" }
        }
    }

    data class ConnectionFailed(
        val failure: ProxyServerFailure,
        val errorResponseBytesWritten: Int,
    ) : ConnectTunnelOutboundExchangeRelayHandlingResult {
        init {
            require(
                failure == ProxyServerFailure.SelectedRouteUnavailable ||
                    failure == ProxyServerFailure.DnsResolutionFailed ||
                    failure == ProxyServerFailure.OutboundConnectionFailed ||
                    failure == ProxyServerFailure.OutboundConnectionTimeout,
            ) { "Connection failures must use an outbound connection failure category" }
            require(errorResponseBytesWritten >= 0) { "Error response bytes written must be non-negative" }
        }
    }

    data class UnsupportedAcceptedRequest(
        val reason: ConnectTunnelOutboundExchangeUnsupportedReason,
    ) : ConnectTunnelOutboundExchangeRelayHandlingResult
}

enum class ConnectTunnelOutboundExchangeUnsupportedReason {
    NotConnectTunnelRequest,
}

class ConnectTunnelOutboundExchangeHandler(
    private val connector: OutboundConnectTunnelConnector,
) {
    fun handle(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientOutput: OutputStream,
    ): ConnectTunnelOutboundExchangeHandlingResult {
        val request =
            accepted.request as? ParsedProxyRequest.ConnectTunnel
                ?: return ConnectTunnelOutboundExchangeHandlingResult.UnsupportedAcceptedRequest(
                    ConnectTunnelOutboundExchangeUnsupportedReason.NotConnectTunnelRequest,
                )

        return when (val openResult = connector.open(request)) {
            is OutboundConnectTunnelOpenResult.Connected ->
                establishTunnel(
                    connection = openResult.connection,
                    clientOutput = clientOutput,
                )
            is OutboundConnectTunnelOpenResult.Failed ->
                writeMappedFailure(
                    failure = openResult.reason.toProxyServerFailure(),
                    clientOutput = clientOutput,
                )
        }
    }

    fun handleAndRelay(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        client: ConnectTunnelClientConnection,
        relayBufferSize: Int = DEFAULT_TUNNEL_RELAY_BUFFER_BYTES,
    ): ConnectTunnelOutboundExchangeRelayHandlingResult {
        require(relayBufferSize > 0) { "Relay buffer size must be positive" }

        val request =
            accepted.request as? ParsedProxyRequest.ConnectTunnel
                ?: return ConnectTunnelOutboundExchangeRelayHandlingResult.UnsupportedAcceptedRequest(
                    ConnectTunnelOutboundExchangeUnsupportedReason.NotConnectTunnelRequest,
                )

        return when (val openResult = connector.open(request)) {
            is OutboundConnectTunnelOpenResult.Connected -> {
                val established =
                    establishTunnel(
                        connection = openResult.connection,
                        clientOutput = client.output,
                    )
                val relayResult =
                    ConnectTunnelBidirectionalRelay.relay(
                        client = client,
                        origin = established.connection,
                        bufferSize = relayBufferSize,
                    )

                ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed(
                    host = established.host,
                    port = established.port,
                    responseBytesWritten = established.responseBytesWritten,
                    relayResult = relayResult,
                )
            }
            is OutboundConnectTunnelOpenResult.Failed -> {
                val failed =
                    writeMappedFailure(
                        failure = openResult.reason.toProxyServerFailure(),
                        clientOutput = client.output,
                    )

                ConnectTunnelOutboundExchangeRelayHandlingResult.ConnectionFailed(
                    failure = failed.failure,
                    errorResponseBytesWritten = failed.errorResponseBytesWritten,
                )
            }
        }
    }

    private fun establishTunnel(
        connection: OutboundConnectTunnelConnection,
        clientOutput: OutputStream,
    ): ConnectTunnelOutboundExchangeHandlingResult.Established {
        val responseBytes = ConnectTunnelEstablishedResponse().toByteArray()

        try {
            clientOutput.write(responseBytes)
            clientOutput.flush()
        } catch (throwable: Throwable) {
            try {
                connection.close()
            } catch (closeFailure: Throwable) {
                throwable.addSuppressed(closeFailure)
            }
            throw throwable
        }

        return ConnectTunnelOutboundExchangeHandlingResult.Established(
            connection = connection,
            host = connection.host,
            port = connection.port,
            responseBytesWritten = responseBytes.size,
        )
    }

    private fun writeMappedFailure(
        failure: ProxyServerFailure,
        clientOutput: OutputStream,
    ): ConnectTunnelOutboundExchangeHandlingResult.ConnectionFailed {
        val bytesWritten =
            when (val decision = ProxyErrorResponseMapper.map(failure)) {
                is ProxyErrorResponseDecision.Emit -> {
                    val bytes = decision.response.toByteArray()
                    clientOutput.write(bytes)
                    clientOutput.flush()
                    bytes.size
                }
                ProxyErrorResponseDecision.Suppress -> 0
            }

        return ConnectTunnelOutboundExchangeHandlingResult.ConnectionFailed(
            failure = failure,
            errorResponseBytesWritten = bytesWritten,
        )
    }
}

private val VALID_CONNECT_TUNNEL_PORT_RANGE = 1..65535
