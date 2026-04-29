package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import java.io.InputStream
import java.io.OutputStream

sealed interface HttpProxyStreamExchangeForwardingResult {
    data class Forwarded(
        val host: String,
        val port: Int,
        val requestHeaderBytesWritten: Int,
        val requestBodyBytesWritten: Long,
        val responseStatusCode: Int,
        val responseHeaderBytesRead: Int,
        val responseHeaderBytesWritten: Int,
        val responseBodyBytesWritten: Long,
        val mustCloseClientConnection: Boolean,
    ) : HttpProxyStreamExchangeForwardingResult {
        init {
            require(host.isNotBlank()) { "Forward host must not be blank" }
            require(port in 1..65535) { "Forward port must be in range 1..65535" }
            require(requestHeaderBytesWritten > 0) { "Request header bytes written must be positive" }
            require(requestBodyBytesWritten >= 0) { "Request body bytes written must be non-negative" }
            require(responseStatusCode in HTTP_RESPONSE_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
            require(responseHeaderBytesRead > 0) { "Response header bytes read must be positive" }
            require(responseHeaderBytesWritten > 0) { "Response header bytes written must be positive" }
            require(responseBodyBytesWritten >= 0) { "Response body bytes written must be non-negative" }
        }
    }

    data class RequestForwardingFailed(
        val result: HttpProxyRequestStreamForwardingResult,
    ) : HttpProxyStreamExchangeForwardingResult {
        init {
            require(result !is HttpProxyRequestStreamForwardingResult.Forwarded) {
                "Request forwarding failures cannot wrap forwarded results"
            }
        }
    }

    data class OriginResponsePreflightRejected(
        val reason: OriginHttpResponseStreamPreflightRejectionReason,
        val responseHeaderBytesRead: Int,
        val requestBodyBytesWritten: Long = 0,
    ) : HttpProxyStreamExchangeForwardingResult {
        init {
            require(responseHeaderBytesRead >= 0) { "Response header bytes read must be non-negative" }
            require(requestBodyBytesWritten >= 0) { "Request body bytes written must be non-negative" }
        }
    }

    data class ResponseForwardingFailed(
        val responseHeaderBytesRead: Int,
        val requestBodyBytesWritten: Long = 0,
        val result: HttpProxyResponseStreamForwardingResult,
    ) : HttpProxyStreamExchangeForwardingResult {
        init {
            require(responseHeaderBytesRead > 0) { "Response header bytes read must be positive" }
            require(requestBodyBytesWritten >= 0) { "Request body bytes written must be non-negative" }
            require(result !is HttpProxyResponseStreamForwardingResult.Forwarded) {
                "Response forwarding failures cannot wrap forwarded results"
            }
        }
    }
}

object HttpProxyStreamExchangeForwarder {
    fun forward(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientInput: InputStream,
        originInput: InputStream,
        originOutput: OutputStream,
        clientOutput: OutputStream,
        bufferSize: Int = DEFAULT_EXCHANGE_FORWARD_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES,
    ): HttpProxyStreamExchangeForwardingResult {
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
        require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
        require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }

        val requestResult =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted,
                input = clientInput,
                output = originOutput,
                bufferSize = bufferSize,
            )
        if (requestResult !is HttpProxyRequestStreamForwardingResult.Forwarded) {
            return HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed(requestResult)
        }

        val responsePreflightResult =
            OriginHttpResponseStreamPreflight.evaluate(
                input = originInput,
                maxHeaderBytes = maxOriginResponseHeaderBytes,
            )
        val acceptedResponse =
            when (responsePreflightResult) {
                is OriginHttpResponseStreamPreflightResult.Accepted -> responsePreflightResult
                is OriginHttpResponseStreamPreflightResult.Rejected ->
                    return HttpProxyStreamExchangeForwardingResult.OriginResponsePreflightRejected(
                        reason = responsePreflightResult.reason,
                        responseHeaderBytesRead = responsePreflightResult.headerBytesRead,
                        requestBodyBytesWritten = requestResult.bodyBytesWritten,
                    )
            }

        val responseResult =
            HttpProxyResponseStreamForwarder.forward(
                response = acceptedResponse.response,
                requestMethod = accepted.httpRequest.request.methodForResponseFraming(),
                input = originInput,
                output = clientOutput,
                bufferSize = bufferSize,
                maxChunkHeaderBytes = maxResponseChunkHeaderBytes,
                maxTrailerBytes = maxResponseTrailerBytes,
            )

        return when (responseResult) {
            is HttpProxyResponseStreamForwardingResult.Forwarded ->
                HttpProxyStreamExchangeForwardingResult.Forwarded(
                    host = requestResult.host,
                    port = requestResult.port,
                    requestHeaderBytesWritten = requestResult.headerBytesWritten,
                    requestBodyBytesWritten = requestResult.bodyBytesWritten,
                    responseStatusCode = responseResult.statusCode,
                    responseHeaderBytesRead = acceptedResponse.headerBytesRead,
                    responseHeaderBytesWritten = responseResult.headerBytesWritten,
                    responseBodyBytesWritten = responseResult.bodyBytesWritten,
                    mustCloseClientConnection = responseResult.mustCloseClientConnection,
                )
            else ->
                HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed(
                    responseHeaderBytesRead = acceptedResponse.headerBytesRead,
                    requestBodyBytesWritten = requestResult.bodyBytesWritten,
                    result = responseResult,
                )
        }
    }
}

private fun com.cellularproxy.proxy.protocol.ParsedProxyRequest.methodForResponseFraming(): String? = when (this) {
    is com.cellularproxy.proxy.protocol.ParsedProxyRequest.HttpProxy -> method
    else -> null
}

private const val DEFAULT_EXCHANGE_FORWARD_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES = 16 * 1024
private const val DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES = 16 * 1024
private val HTTP_RESPONSE_STATUS_CODE_RANGE = 100..599
