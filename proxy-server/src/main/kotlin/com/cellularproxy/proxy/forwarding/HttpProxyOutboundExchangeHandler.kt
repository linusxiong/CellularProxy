package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

fun interface OutboundHttpOriginConnector {
    fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult
}

data class OutboundHttpOriginConnection(
    val input: InputStream,
    val output: OutputStream,
    val host: String,
    val port: Int,
) : Closeable {
    init {
        require(host.isNotBlank()) { "Origin host must not be blank" }
        require(port in VALID_PORT_RANGE) { "Origin port must be in range 1..65535" }
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

sealed interface OutboundHttpOriginOpenResult {
    data class Connected(
        val connection: OutboundHttpOriginConnection,
    ) : OutboundHttpOriginOpenResult

    data class Failed(
        val reason: OutboundHttpOriginOpenFailure,
    ) : OutboundHttpOriginOpenResult
}

enum class OutboundHttpOriginOpenFailure {
    SelectedRouteUnavailable,
    DnsResolutionFailed,
    OutboundConnectionFailed,
    OutboundConnectionTimeout,
}

fun OutboundHttpOriginOpenFailure.toProxyServerFailure(): ProxyServerFailure = when (this) {
    OutboundHttpOriginOpenFailure.SelectedRouteUnavailable -> ProxyServerFailure.SelectedRouteUnavailable
    OutboundHttpOriginOpenFailure.DnsResolutionFailed -> ProxyServerFailure.DnsResolutionFailed
    OutboundHttpOriginOpenFailure.OutboundConnectionFailed -> ProxyServerFailure.OutboundConnectionFailed
    OutboundHttpOriginOpenFailure.OutboundConnectionTimeout -> ProxyServerFailure.OutboundConnectionTimeout
}

sealed interface HttpProxyOutboundExchangeHandlingResult {
    data class Forwarded(
        val result: HttpProxyStreamExchangeForwardingResult,
    ) : HttpProxyOutboundExchangeHandlingResult

    data class ConnectionFailed(
        val failure: ProxyServerFailure,
        val errorResponseBytesWritten: Int,
    ) : HttpProxyOutboundExchangeHandlingResult {
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
        val reason: HttpProxyOutboundExchangeUnsupportedReason,
    ) : HttpProxyOutboundExchangeHandlingResult
}

enum class HttpProxyOutboundExchangeUnsupportedReason {
    NotPlainHttpProxyRequest,
}

class HttpProxyOutboundExchangeHandler(
    private val connector: OutboundHttpOriginConnector,
) {
    fun handle(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientInput: InputStream,
        clientOutput: OutputStream,
        bufferSize: Int = DEFAULT_EXCHANGE_FORWARD_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES,
    ): HttpProxyOutboundExchangeHandlingResult {
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
        require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
        require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }

        val request =
            accepted.request as? ParsedProxyRequest.HttpProxy
                ?: return HttpProxyOutboundExchangeHandlingResult.UnsupportedAcceptedRequest(
                    HttpProxyOutboundExchangeUnsupportedReason.NotPlainHttpProxyRequest,
                )

        return when (val openResult = connector.open(request)) {
            is OutboundHttpOriginOpenResult.Connected ->
                handleConnectedExchange(
                    accepted = accepted,
                    clientInput = clientInput,
                    clientOutput = clientOutput,
                    connection = openResult.connection,
                    bufferSize = bufferSize,
                    maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                    maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                    maxResponseTrailerBytes = maxResponseTrailerBytes,
                )
            is OutboundHttpOriginOpenResult.Failed ->
                writeMappedFailure(
                    failure = openResult.reason.toProxyServerFailure(),
                    clientOutput = clientOutput,
                )
        }
    }

    private fun handleConnectedExchange(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientInput: InputStream,
        clientOutput: OutputStream,
        connection: OutboundHttpOriginConnection,
        bufferSize: Int,
        maxOriginResponseHeaderBytes: Int,
        maxResponseChunkHeaderBytes: Int,
        maxResponseTrailerBytes: Int,
    ): HttpProxyOutboundExchangeHandlingResult.Forwarded = try {
        HttpProxyOutboundExchangeHandlingResult.Forwarded(
            HttpProxyStreamExchangeForwarder.forward(
                accepted = accepted,
                clientInput = clientInput,
                originInput = connection.input,
                originOutput = connection.output,
                clientOutput = clientOutput,
                bufferSize = bufferSize,
                maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                maxResponseTrailerBytes = maxResponseTrailerBytes,
            ),
        )
    } finally {
        connection.closeAfterExchange()
    }

    private fun writeMappedFailure(
        failure: ProxyServerFailure,
        clientOutput: OutputStream,
    ): HttpProxyOutboundExchangeHandlingResult.ConnectionFailed {
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

        return HttpProxyOutboundExchangeHandlingResult.ConnectionFailed(
            failure = failure,
            errorResponseBytesWritten = bytesWritten,
        )
    }
}

private fun OutboundHttpOriginConnection.closeAfterExchange() {
    try {
        close()
    } catch (_: Exception) {
        // Close failures after an exchange should not replace the exchange outcome.
    }
}

private const val DEFAULT_EXCHANGE_FORWARD_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES = 16 * 1024
private const val DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES = 16 * 1024
private val VALID_PORT_RANGE = 1..65535
