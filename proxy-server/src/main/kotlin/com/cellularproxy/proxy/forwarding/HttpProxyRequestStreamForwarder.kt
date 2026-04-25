package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpBodyFramingStreamCopier
import com.cellularproxy.proxy.protocol.HttpBodyStreamCopyResult
import com.cellularproxy.proxy.protocol.HttpRequestBodyFramingPolicy
import com.cellularproxy.proxy.protocol.HttpRequestBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.HttpRequestBodyFramingResult
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

sealed interface HttpProxyRequestStreamForwardingResult {
    data class Forwarded(
        val host: String,
        val port: Int,
        val headerBytesWritten: Int,
        val bodyBytesWritten: Long,
    ) : HttpProxyRequestStreamForwardingResult {
        init {
            require(host.isNotBlank()) { "Forward host must not be blank" }
            require(port in 1..65535) { "Forward port must be in range 1..65535" }
            require(headerBytesWritten > 0) { "Header bytes written must be positive" }
            require(bodyBytesWritten >= 0) { "Body bytes written must be non-negative" }
        }
    }

    data class BodyCopyFailed(
        val host: String,
        val port: Int,
        val headerBytesWritten: Int,
        val copyResult: HttpBodyStreamCopyResult,
    ) : HttpProxyRequestStreamForwardingResult {
        init {
            require(host.isNotBlank()) { "Forward host must not be blank" }
            require(port in 1..65535) { "Forward port must be in range 1..65535" }
            require(headerBytesWritten > 0) { "Header bytes written must be positive" }
            require(copyResult !is HttpBodyStreamCopyResult.Completed) {
                "Body copy failures cannot wrap completed copy results"
            }
        }
    }

    data class Rejected(
        val reason: HttpProxyRequestStreamForwardingRejectionReason,
    ) : HttpProxyRequestStreamForwardingResult
}

sealed interface HttpProxyRequestStreamForwardingRejectionReason {
    data object NotHttpProxyRequest : HttpProxyRequestStreamForwardingRejectionReason
    data object UnsupportedExpectHeader : HttpProxyRequestStreamForwardingRejectionReason
    data class BodyFramingRejected(
        val reason: HttpRequestBodyFramingRejectionReason,
    ) : HttpProxyRequestStreamForwardingRejectionReason
}

object HttpProxyRequestStreamForwarder {
    fun forward(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_REQUEST_FORWARD_BUFFER_BYTES,
    ): HttpProxyRequestStreamForwardingResult =
        forwardAcceptedRequest(
            request = accepted.httpRequest,
            input = input,
            output = output,
            bufferSize = bufferSize,
        )

    private fun forwardAcceptedRequest(
        request: ParsedHttpRequest,
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_REQUEST_FORWARD_BUFFER_BYTES,
    ): HttpProxyRequestStreamForwardingResult {
        require(bufferSize > 0) { "Buffer size must be positive" }

        if (request.request !is ParsedProxyRequest.HttpProxy) {
            return HttpProxyRequestStreamForwardingResult.Rejected(
                HttpProxyRequestStreamForwardingRejectionReason.NotHttpProxyRequest,
            )
        }

        if (request.headers.hasHeader(EXPECT_HEADER)) {
            return HttpProxyRequestStreamForwardingResult.Rejected(
                HttpProxyRequestStreamForwardingRejectionReason.UnsupportedExpectHeader,
            )
        }

        val framing = when (val result = HttpRequestBodyFramingPolicy.classify(request)) {
            is HttpRequestBodyFramingResult.Accepted -> result.framing
            is HttpRequestBodyFramingResult.Rejected -> {
                return HttpProxyRequestStreamForwardingResult.Rejected(
                    HttpProxyRequestStreamForwardingRejectionReason.BodyFramingRejected(result.reason),
                )
            }
        }

        val requestHead = HttpProxyForwardRequestRenderer.renderHead(request)
        val requestHeadBytes = requestHead.toByteArray()
        output.write(requestHeadBytes)

        return when (
            val bodyCopyResult = HttpBodyFramingStreamCopier.copyRequestBody(
                framing = framing,
                input = input,
                output = output,
                bufferSize = bufferSize,
            )
        ) {
            is HttpBodyStreamCopyResult.Completed ->
                HttpProxyRequestStreamForwardingResult.Forwarded(
                    host = requestHead.host,
                    port = requestHead.port,
                    headerBytesWritten = requestHeadBytes.size,
                    bodyBytesWritten = bodyCopyResult.bytesCopied,
                )
            else ->
                HttpProxyRequestStreamForwardingResult.BodyCopyFailed(
                    host = requestHead.host,
                    port = requestHead.port,
                    headerBytesWritten = requestHeadBytes.size,
                    copyResult = bodyCopyResult,
                )
        }
    }
}

private fun Map<String, List<String>>.hasHeader(name: String): Boolean {
    val normalizedName = name.lowercase(Locale.US)
    return keys.any { headerName -> headerName.lowercase(Locale.US) == normalizedName }
}

private const val DEFAULT_REQUEST_FORWARD_BUFFER_BYTES = 8 * 1024
private const val EXPECT_HEADER = "expect"
