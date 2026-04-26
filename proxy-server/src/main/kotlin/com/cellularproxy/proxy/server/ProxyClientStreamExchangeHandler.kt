package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.ConnectTunnelClientConnection
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeHandler
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandler
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflight
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandler
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.InputStream
import java.io.OutputStream

data class ProxyClientStreamConnection(
    val input: InputStream,
    val output: OutputStream,
    private val shutdownInputAction: () -> Unit = { input.close() },
    private val shutdownOutputAction: () -> Unit = { output.close() },
) {
    fun toConnectTunnelClientConnection(): ConnectTunnelClientConnection =
        ConnectTunnelClientConnection(
            input = input,
            output = output,
            shutdownInputAction = shutdownInputAction,
            shutdownOutputAction = shutdownOutputAction,
        )
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
        val result: HttpProxyOutboundExchangeHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult

    data class ConnectTunnelHandled(
        val result: ConnectTunnelOutboundExchangeRelayHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult

    data class ManagementHandled(
        val result: ManagementApiStreamExchangeHandlingResult,
    ) : ProxyClientStreamExchangeHandlingResult
}

class ProxyClientStreamExchangeHandler(
    httpConnector: OutboundHttpOriginConnector,
    connectConnector: OutboundConnectTunnelConnector,
    managementHandler: ManagementApiHandler,
) {
    private val httpHandler = HttpProxyOutboundExchangeHandler(httpConnector)
    private val connectHandler = ConnectTunnelOutboundExchangeHandler(connectConnector)
    private val managementHandler = ManagementApiStreamExchangeHandler(managementHandler)

    fun handle(
        config: ProxyIngressPreflightConfig,
        activeConnections: Long,
        client: ProxyClientStreamConnection,
        httpBufferSize: Int = DEFAULT_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_CONNECT_RELAY_BUFFER_BYTES,
    ): ProxyClientStreamExchangeHandlingResult {
        require(httpBufferSize > 0) { "HTTP buffer size must be positive" }
        require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
        require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
        require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }
        require(connectRelayBufferSize > 0) { "CONNECT relay buffer size must be positive" }

        val preflight = ProxyIngressStreamPreflight.evaluate(
            config = config,
            activeConnections = activeConnections,
            input = client.input,
        )

        return when (preflight) {
            is ProxyIngressStreamPreflightDecision.Accepted ->
                handleAccepted(
                    accepted = preflight,
                    client = client,
                    httpBufferSize = httpBufferSize,
                    maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                    maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                    maxResponseTrailerBytes = maxResponseTrailerBytes,
                    connectRelayBufferSize = connectRelayBufferSize,
                )
            is ProxyIngressStreamPreflightDecision.Rejected ->
                writePreflightRejection(preflight, client.output)
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
    ): ProxyClientStreamExchangeHandlingResult =
        when (accepted.request) {
            is ParsedProxyRequest.HttpProxy ->
                ProxyClientStreamExchangeHandlingResult.HttpProxyHandled(
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
                    connectHandler.handleAndRelay(
                        accepted = accepted,
                        client = client.toConnectTunnelClientConnection(),
                        relayBufferSize = connectRelayBufferSize,
                    ),
                )
            is ParsedProxyRequest.Management ->
                ProxyClientStreamExchangeHandlingResult.ManagementHandled(
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
        val bytesWritten = when (val response = rejected.response) {
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
}

private const val DEFAULT_HTTP_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_ORIGIN_RESPONSE_HEADER_BYTES = 16 * 1024
private const val DEFAULT_RESPONSE_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_TRAILER_BYTES = 16 * 1024
private const val DEFAULT_CONNECT_RELAY_BUFFER_BYTES = 8 * 1024
