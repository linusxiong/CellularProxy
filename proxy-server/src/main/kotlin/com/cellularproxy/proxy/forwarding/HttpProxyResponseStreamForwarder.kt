package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpBodyFramingStreamCopier
import com.cellularproxy.proxy.protocol.HttpBodyStreamCopyResult
import com.cellularproxy.proxy.protocol.HttpResponseBodyFraming
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingPolicy
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingResult
import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import java.io.InputStream
import java.io.OutputStream

sealed interface HttpProxyResponseStreamForwardingResult {
    data class Forwarded(
        val statusCode: Int,
        val headerBytesWritten: Int,
        val bodyBytesWritten: Long,
        val mustCloseClientConnection: Boolean,
    ) : HttpProxyResponseStreamForwardingResult {
        init {
            require(statusCode in HTTP_RESPONSE_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
            require(headerBytesWritten > 0) { "Header bytes written must be positive" }
            require(bodyBytesWritten >= 0) { "Body bytes written must be non-negative" }
        }
    }

    data class BodyCopyFailed(
        val statusCode: Int,
        val headerBytesWritten: Int,
        val mustCloseClientConnection: Boolean,
        val copyResult: HttpBodyStreamCopyResult,
    ) : HttpProxyResponseStreamForwardingResult {
        init {
            require(statusCode in HTTP_RESPONSE_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
            require(headerBytesWritten > 0) { "Header bytes written must be positive" }
            require(copyResult !is HttpBodyStreamCopyResult.Completed) {
                "Body copy failures cannot wrap completed copy results"
            }
        }
    }

    data class Rejected(
        val reason: HttpProxyResponseStreamForwardingRejectionReason,
    ) : HttpProxyResponseStreamForwardingResult
}

sealed interface HttpProxyResponseStreamForwardingRejectionReason {
    data class BodyFramingRejected(
        val reason: HttpResponseBodyFramingRejectionReason,
    ) : HttpProxyResponseStreamForwardingRejectionReason

    data object ResponseHeadRejected : HttpProxyResponseStreamForwardingRejectionReason
}

object HttpProxyResponseStreamForwarder {
    fun forward(
        response: ParsedHttpResponse,
        requestMethod: String?,
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_RESPONSE_FORWARD_BUFFER_BYTES,
        maxChunkHeaderBytes: Int = DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES,
        maxTrailerBytes: Int = DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES,
    ): HttpProxyResponseStreamForwardingResult {
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(maxChunkHeaderBytes > 0) { "Maximum chunk header bytes must be positive" }
        require(maxTrailerBytes >= 0) { "Maximum trailer bytes must be non-negative" }

        val framing = when (
            val result = HttpResponseBodyFramingPolicy.classify(
                response = response,
                requestMethod = requestMethod,
            )
        ) {
            is HttpResponseBodyFramingResult.Accepted -> result.framing
            is HttpResponseBodyFramingResult.Rejected -> {
                return HttpProxyResponseStreamForwardingResult.Rejected(
                    HttpProxyResponseStreamForwardingRejectionReason.BodyFramingRejected(result.reason),
                )
            }
        }

        val responseHead = try {
            HttpProxyForwardResponseRenderer.renderHead(response)
        } catch (_: IllegalArgumentException) {
            return HttpProxyResponseStreamForwardingResult.Rejected(
                HttpProxyResponseStreamForwardingRejectionReason.ResponseHeadRejected,
            )
        }
        val responseHeadBytes = responseHead.toByteArray()
        output.write(responseHeadBytes)

        return when (
            val bodyCopyResult = HttpBodyFramingStreamCopier.copyResponseBody(
                framing = framing,
                input = input,
                output = output,
                bufferSize = bufferSize,
                maxChunkHeaderBytes = maxChunkHeaderBytes,
                maxTrailerBytes = maxTrailerBytes,
            )
        ) {
            is HttpBodyStreamCopyResult.Completed ->
                HttpProxyResponseStreamForwardingResult.Forwarded(
                    statusCode = responseHead.statusCode,
                    headerBytesWritten = responseHeadBytes.size,
                    bodyBytesWritten = bodyCopyResult.bytesCopied,
                    mustCloseClientConnection = framing.requiresClientConnectionClose(),
                )
            else ->
                HttpProxyResponseStreamForwardingResult.BodyCopyFailed(
                    statusCode = responseHead.statusCode,
                    headerBytesWritten = responseHeadBytes.size,
                    mustCloseClientConnection = framing.requiresClientConnectionClose(),
                    copyResult = bodyCopyResult,
                )
        }
    }

    private fun HttpResponseBodyFraming.requiresClientConnectionClose(): Boolean =
        this is HttpResponseBodyFraming.CloseDelimited
}

private const val DEFAULT_RESPONSE_FORWARD_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_FORWARD_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RESPONSE_FORWARD_TRAILER_BYTES = 16 * 1024
private val HTTP_RESPONSE_STATUS_CODE_RANGE = 100..599
