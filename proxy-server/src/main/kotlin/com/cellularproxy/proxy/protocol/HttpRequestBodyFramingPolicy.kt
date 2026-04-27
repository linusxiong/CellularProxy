package com.cellularproxy.proxy.protocol

import java.util.Locale

sealed interface HttpRequestBodyFraming {
    data object NoBody : HttpRequestBodyFraming

    data class FixedLength(
        val contentLength: Long,
    ) : HttpRequestBodyFraming {
        init {
            require(contentLength >= 0) { "Content length must be non-negative" }
        }
    }
}

sealed interface HttpRequestBodyFramingResult {
    data class Accepted(
        val framing: HttpRequestBodyFraming,
    ) : HttpRequestBodyFramingResult

    data class Rejected(
        val reason: HttpRequestBodyFramingRejectionReason,
    ) : HttpRequestBodyFramingResult
}

enum class HttpRequestBodyFramingRejectionReason {
    AmbiguousContentLength,
    InvalidContentLength,
    UnsupportedTransferEncoding,
    BodyNotSupported,
}

object HttpRequestBodyFramingPolicy {
    fun classify(request: ParsedHttpRequest): HttpRequestBodyFramingResult {
        val transferEncodingValues =
            request.headers.requestBodyFramingCaseInsensitiveValues(REQUEST_BODY_FRAMING_TRANSFER_ENCODING_HEADER)
        val contentLengthValues =
            request.headers.requestBodyFramingCaseInsensitiveValues(REQUEST_BODY_FRAMING_CONTENT_LENGTH_HEADER)

        if (request.request !is ParsedProxyRequest.HttpProxy) {
            return if (transferEncodingValues.isEmpty() && contentLengthValues.isEmpty()) {
                HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.NoBody)
            } else {
                HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.BodyNotSupported)
            }
        }

        if (transferEncodingValues.isNotEmpty()) {
            return HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.UnsupportedTransferEncoding)
        }

        if (contentLengthValues.isEmpty()) {
            return HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.NoBody)
        }
        if (contentLengthValues.size != 1) {
            return HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.AmbiguousContentLength)
        }

        val contentLength = contentLengthValues.single()
        if (!contentLength.isRequestBodyFramingAsciiDecimalDigits()) {
            return HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.InvalidContentLength)
        }

        val parsedContentLength =
            contentLength.toLongOrNull()
                ?: return HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.InvalidContentLength)

        return HttpRequestBodyFramingResult.Accepted(
            HttpRequestBodyFraming.FixedLength(parsedContentLength),
        )
    }

    private fun Map<String, List<String>>.requestBodyFramingCaseInsensitiveValues(name: String): List<String> {
        val normalizedName = name.lowercase(Locale.US)
        return entries
            .filter { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }
            .flatMap { (_, values) -> values }
    }

    private fun String.isRequestBodyFramingAsciiDecimalDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
}

private const val REQUEST_BODY_FRAMING_CONTENT_LENGTH_HEADER = "content-length"
private const val REQUEST_BODY_FRAMING_TRANSFER_ENCODING_HEADER = "transfer-encoding"
