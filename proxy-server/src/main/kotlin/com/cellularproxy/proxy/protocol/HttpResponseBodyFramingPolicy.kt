package com.cellularproxy.proxy.protocol

import java.util.Locale

sealed interface HttpResponseBodyFraming {
    data object NoBody : HttpResponseBodyFraming

    data class FixedLength(
        val contentLength: Long,
    ) : HttpResponseBodyFraming {
        init {
            require(contentLength >= 0) { "Content length must be non-negative" }
        }
    }

    data object Chunked : HttpResponseBodyFraming

    data object CloseDelimited : HttpResponseBodyFraming
}

sealed interface HttpResponseBodyFramingResult {
    data class Accepted(
        val framing: HttpResponseBodyFraming,
    ) : HttpResponseBodyFramingResult

    data class Rejected(
        val reason: HttpResponseBodyFramingRejectionReason,
    ) : HttpResponseBodyFramingResult
}

enum class HttpResponseBodyFramingRejectionReason {
    AmbiguousContentLength,
    InvalidContentLength,
    UnsupportedTransferEncoding,
}

object HttpResponseBodyFramingPolicy {
    fun classify(
        response: ParsedHttpResponse,
        requestMethod: String? = null,
    ): HttpResponseBodyFramingResult {
        if (requestMethod == HEAD_METHOD || response.statusCode.forbidsResponseBody()) {
            return HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.NoBody)
        }

        val transferEncodingValues = response.headers.caseInsensitiveValues(BODY_FRAMING_TRANSFER_ENCODING_HEADER)
        if (transferEncodingValues.isNotEmpty()) {
            return if (transferEncodingValues.isSupportedChunkedTransferEncoding()) {
                HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.Chunked)
            } else {
                HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.UnsupportedTransferEncoding)
            }
        }

        val contentLengthValues = response.headers.caseInsensitiveValues(BODY_FRAMING_CONTENT_LENGTH_HEADER)
        if (contentLengthValues.isNotEmpty()) {
            val uniqueContentLengths = contentLengthValues.toSet()
            if (uniqueContentLengths.size != 1) {
                return HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.AmbiguousContentLength)
            }

            val contentLength = uniqueContentLengths.single()
            if (!contentLength.isAsciiDecimalDigits()) {
                return HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.InvalidContentLength)
            }

            val parsedContentLength =
                contentLength.toLongOrNull()
                    ?: return HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.InvalidContentLength)

            return HttpResponseBodyFramingResult.Accepted(
                HttpResponseBodyFraming.FixedLength(parsedContentLength),
            )
        }

        return HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.CloseDelimited)
    }

    private fun Map<String, List<String>>.caseInsensitiveValues(name: String): List<String> {
        val normalizedName = name.lowercase(Locale.US)
        return entries
            .filter { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }
            .flatMap { (_, values) -> values }
    }

    private fun List<String>.isSupportedChunkedTransferEncoding(): Boolean {
        val codings =
            flatMap { value -> value.split(',') }
                .map { coding -> coding.trim().lowercase(Locale.US) }
        return codings == listOf(BODY_FRAMING_CHUNKED_TRANSFER_CODING)
    }

    private fun String.isAsciiDecimalDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    private fun Int.forbidsResponseBody(): Boolean =
        this in BODY_FRAMING_INFORMATIONAL_STATUS_RANGE ||
            this == BODY_FRAMING_NO_CONTENT_STATUS ||
            this == BODY_FRAMING_NOT_MODIFIED_STATUS
}

private const val HEAD_METHOD = "HEAD"
private const val BODY_FRAMING_CONTENT_LENGTH_HEADER = "content-length"
private const val BODY_FRAMING_TRANSFER_ENCODING_HEADER = "transfer-encoding"
private const val BODY_FRAMING_CHUNKED_TRANSFER_CODING = "chunked"
private const val BODY_FRAMING_NO_CONTENT_STATUS = 204
private const val BODY_FRAMING_NOT_MODIFIED_STATUS = 304
private val BODY_FRAMING_INFORMATIONAL_STATUS_RANGE = 100..199
