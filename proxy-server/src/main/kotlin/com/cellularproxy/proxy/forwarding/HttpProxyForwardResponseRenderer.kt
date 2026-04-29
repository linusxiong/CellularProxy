package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale

class ForwardedHttpResponseHead(
    val statusCode: Int,
    val reasonPhrase: String,
    headers: Map<String, List<String>>,
) {
    val headers: Map<String, List<String>>

    init {
        require(statusCode in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
        require(reasonPhrase.isSafeSingleLine()) { "Reason phrase must not contain control characters" }

        val copiedHeaders = headers.validatedHeaderSnapshot()
        val contentLengthValues = copiedHeaders.caseInsensitiveHeaderValues(CONTENT_LENGTH_HEADER)
        val transferEncodingValues = copiedHeaders.caseInsensitiveHeaderValues(TRANSFER_ENCODING_HEADER)
        if (statusCode.forbidsResponseBody()) {
            require(transferEncodingValues.isEmpty()) {
                "Response status $statusCode must not contain Transfer-Encoding"
            }
            require(statusCode == NOT_MODIFIED_STATUS || contentLengthValues.isEmpty()) {
                "Response status $statusCode must not contain Content-Length"
            }
        }
        require(contentLengthValues.size <= 1) {
            "Forwarded response heads must not contain duplicate Content-Length headers"
        }
        require(contentLengthValues.isEmpty() || transferEncodingValues.isEmpty()) {
            "Forwarded response heads must not contain both Content-Length and Transfer-Encoding"
        }
        contentLengthValues.singleOrNull()?.let { contentLength ->
            require(contentLength.isDecimalDigits()) {
                "Content-Length must be a non-negative decimal value"
            }
            require(contentLength.toLongOrNull() != null) {
                "Content-Length must fit in a signed 64-bit integer"
            }
        }
        if (transferEncodingValues.isNotEmpty()) {
            require(transferEncodingValues.isSupportedChunkedTransferEncoding()) {
                "Only chunked Transfer-Encoding is supported for forwarded response heads"
            }
        }

        this.headers = copiedHeaders
    }

    fun toHttpString(): String = headerString()

    fun toByteArray(): ByteArray = headerString().toByteArray(Charsets.UTF_8)

    private fun headerString(): String = renderHttpResponseHead(statusCode, reasonPhrase, headers)
}

class ForwardedHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    headers: Map<String, List<String>>,
    body: ByteArray = EMPTY_BODY,
) {
    val headers: Map<String, List<String>>
    private val bodyBytes: ByteArray
    val body: ByteArray
        get() = bodyBytes.copyOf()

    init {
        require(statusCode in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
        require(reasonPhrase.isSafeSingleLine()) { "Reason phrase must not contain control characters" }

        val copiedHeaders = headers.validatedHeaderSnapshot()

        val bodyCopy = body.copyOf()
        require(!statusCode.forbidsResponseBody() || bodyCopy.isEmpty()) {
            "Response status $statusCode must not contain a body"
        }

        val contentLengthValues = copiedHeaders.caseInsensitiveHeaderValues(CONTENT_LENGTH_HEADER)
        val transferEncodingValues = copiedHeaders.caseInsensitiveHeaderValues(TRANSFER_ENCODING_HEADER)
        require(contentLengthValues.size <= 1) {
            "Forwarded responses must not contain duplicate Content-Length headers"
        }
        if (statusCode.forbidsResponseBody()) {
            require(transferEncodingValues.isEmpty()) {
                "Response status $statusCode must not contain Transfer-Encoding"
            }
            require(statusCode == NOT_MODIFIED_STATUS || contentLengthValues.isEmpty()) {
                "Response status $statusCode must not contain Content-Length"
            }
        }
        require(contentLengthValues.isEmpty() || transferEncodingValues.isEmpty()) {
            "Forwarded responses must not contain both Content-Length and Transfer-Encoding"
        }

        val contentLength = contentLengthValues.singleOrNull()
        if (contentLength != null && statusCode != NOT_MODIFIED_STATUS) {
            require(contentLength.isDecimalDigits()) {
                "Content-Length must be a non-negative decimal value"
            }
            val declaredLength = contentLength.toLong()
            require(declaredLength == bodyCopy.size.toLong()) {
                "Content-Length must match the forwarded response body size"
            }
        } else if (contentLength != null) {
            require(contentLength.isDecimalDigits()) {
                "Content-Length must be a non-negative decimal value"
            }
            require(contentLength.toLongOrNull() != null) {
                "Content-Length must fit in a signed 64-bit integer"
            }
        } else if (transferEncodingValues.isNotEmpty()) {
            require(transferEncodingValues.isSupportedChunkedTransferEncoding()) {
                "Only chunked Transfer-Encoding is supported for forwarded responses"
            }
            require(bodyCopy.isValidChunkedBody()) {
                "Chunked response body must contain valid chunk framing"
            }
        } else {
            require(bodyCopy.isEmpty()) {
                "Forwarded response bodies require Content-Length or Transfer-Encoding"
            }
        }

        this.headers = Collections.unmodifiableMap(copiedHeaders)
        this.bodyBytes = bodyCopy
    }

    fun toHttpString(): String = headerString() + bodyBytes.decodeStrictUtf8()

    fun toByteArray(): ByteArray = headerString().toByteArray(Charsets.UTF_8) + bodyBytes

    private fun headerString(): String = renderHttpResponseHead(statusCode, reasonPhrase, headers)
}

object HttpProxyForwardResponseRenderer {
    fun renderHead(response: ParsedHttpResponse): ForwardedHttpResponseHead = renderHead(
        statusCode = response.statusCode,
        reasonPhrase = response.reasonPhrase,
        headers = response.headers,
    )

    fun renderHead(
        statusCode: Int,
        reasonPhrase: String,
        headers: Map<String, List<String>>,
    ): ForwardedHttpResponseHead = ForwardedHttpResponseHead(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = sanitizeResponseHeaders(statusCode, headers),
    )

    fun render(
        statusCode: Int,
        reasonPhrase: String,
        headers: Map<String, List<String>>,
        body: ByteArray = EMPTY_BODY,
    ): ForwardedHttpResponse = ForwardedHttpResponse(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = sanitizeResponseHeaders(statusCode, headers),
        body = body,
    )

    private fun sanitizeResponseHeaders(
        statusCode: Int,
        headers: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val connectionNominatedHeaderNames = headers.connectionNominatedHeaderNames()
        require(TRANSFER_ENCODING_HEADER !in connectionNominatedHeaderNames) {
            "Connection-nominated Transfer-Encoding responses are not supported"
        }

        val headersToStrip = HOP_BY_HOP_RESPONSE_HEADERS + connectionNominatedHeaderNames
        val hasTransferEncoding = headers.caseInsensitiveHeaderValues(TRANSFER_ENCODING_HEADER).isNotEmpty()
        val outboundHeaders = linkedMapOf<String, List<String>>()
        headers.forEach { (headerName, values) ->
            val normalizedHeaderName = headerName.lowercase(Locale.US)
            val stripForNoBodyStatus =
                statusCode.forbidsResponseBody() &&
                    (
                        normalizedHeaderName == TRANSFER_ENCODING_HEADER ||
                            (statusCode != NOT_MODIFIED_STATUS && normalizedHeaderName == CONTENT_LENGTH_HEADER)
                    )
            val stripAmbiguousContentLength =
                !statusCode.forbidsResponseBody() &&
                    hasTransferEncoding &&
                    normalizedHeaderName == CONTENT_LENGTH_HEADER

            if (
                normalizedHeaderName !in headersToStrip &&
                !stripForNoBodyStatus &&
                !stripAmbiguousContentLength
            ) {
                outboundHeaders[headerName] = headerName.canonicalizedResponseHeaderValues(values)
            }
        }
        return outboundHeaders
    }

    private fun Map<String, List<String>>.connectionNominatedHeaderNames(): Set<String> = filterKeys { headerName -> headerName.lowercase(Locale.US) == CONNECTION_HEADER }
        .values
        .flatten()
        .flatMap { value -> value.split(',') }
        .map { option -> option.trim().lowercase(Locale.US) }
        .filter { option -> option.isHttpToken() }
        .toSet()
}

private fun String.canonicalizedResponseHeaderValues(values: List<String>): List<String> = if (lowercase(Locale.US) == CONTENT_LENGTH_HEADER) {
    val uniqueContentLengths = values.toSet()
    require(uniqueContentLengths.size == 1) {
        "Forwarded response heads must not contain ambiguous Content-Length values"
    }
    listOf(uniqueContentLengths.single())
} else {
    values
}

private fun Map<String, List<String>>.validatedHeaderSnapshot(): Map<String, List<String>> {
    val copiedHeaders = linkedMapOf<String, List<String>>()
    forEach { (name, values) ->
        require(name.isHttpToken()) { "Header name must be a valid HTTP token" }
        require(values.isNotEmpty()) { "Header values must not be empty" }
        copiedHeaders[name] =
            Collections.unmodifiableList(
                values.map { value ->
                    require(value.none(Char::isDisallowedHeaderValueControl)) {
                        "Header value must not contain control characters"
                    }
                    value
                },
            )
    }

    val normalizedHeaderNames = copiedHeaders.keys.map { it.lowercase(Locale.US) }
    require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
        "HTTP header names must not contain case-variant duplicates"
    }

    return Collections.unmodifiableMap(copiedHeaders)
}

private fun renderHttpResponseHead(
    statusCode: Int,
    reasonPhrase: String,
    headers: Map<String, List<String>>,
): String = buildString {
    append("HTTP/1.1 ")
    append(statusCode)
    append(' ')
    append(reasonPhrase)
    append(CRLF)
    headers.forEach { (name, values) ->
        values.forEach { value ->
            append(name).append(": ").append(value).append(CRLF)
        }
    }
    append(CRLF)
}

private fun String.isSafeSingleLine(): Boolean = none(Char::isISOControl)

private fun String.isHttpToken(): Boolean = isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun String.isDecimalDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun Char.isDisallowedHeaderValueControl(): Boolean = code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private fun Map<String, List<String>>.caseInsensitiveHeaderValues(name: String): List<String> {
    val normalizedName = name.lowercase(Locale.US)
    return entries
        .filter { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }
        .flatMap { (_, values) -> values }
}

private fun Int.forbidsResponseBody(): Boolean = this in INFORMATIONAL_STATUS_RANGE || this == NO_CONTENT_STATUS || this == NOT_MODIFIED_STATUS

private fun List<String>.isSupportedChunkedTransferEncoding(): Boolean {
    val codings =
        flatMap { value -> value.split(',') }
            .map { coding -> coding.trim().lowercase(Locale.US) }
    return codings == listOf(CHUNKED_TRANSFER_CODING)
}

private fun ByteArray.isValidChunkedBody(): Boolean {
    var index = 0
    while (index < size) {
        val chunkSizeLineEnd = indexOfCrlf(startIndex = index)
        if (chunkSizeLineEnd == -1) {
            return false
        }

        val chunkSizeLine = copyOfRange(index, chunkSizeLineEnd).toAsciiStringOrNull() ?: return false
        val chunkSize = chunkSizeLine.substringBefore(';').trim()
        if (chunkSize.isEmpty() || !chunkSize.all(Char::isHexDigit)) {
            return false
        }

        val decodedChunkSize = chunkSize.toLongOrNull(radix = HEX_RADIX) ?: return false
        if (decodedChunkSize > Int.MAX_VALUE) {
            return false
        }

        index = chunkSizeLineEnd + CRLF.length
        if (decodedChunkSize == 0L) {
            return hasTerminatingTrailerBlock(startIndex = index)
        }

        val chunkDataEnd = index + decodedChunkSize.toInt()
        if (chunkDataEnd > size || !hasCrlfAt(chunkDataEnd)) {
            return false
        }

        index = chunkDataEnd + CRLF.length
    }
    return false
}

private fun ByteArray.hasTerminatingTrailerBlock(startIndex: Int): Boolean {
    var index = startIndex
    while (index <= size) {
        val lineEnd = indexOfCrlf(startIndex = index)
        if (lineEnd == -1) {
            return false
        }
        if (lineEnd == index) {
            return lineEnd + CRLF.length == size
        }
        val trailerLine = copyOfRange(index, lineEnd).toAsciiStringOrNull() ?: return false
        val separator = trailerLine.indexOf(':')
        if (separator <= 0 || !trailerLine.substring(0, separator).isHttpToken()) {
            return false
        }
        if (trailerLine.substring(separator + 1).any(Char::isDisallowedHeaderValueControl)) {
            return false
        }
        index = lineEnd + CRLF.length
    }
    return false
}

private fun ByteArray.indexOfCrlf(startIndex: Int): Int {
    var index = startIndex
    while (index < size - 1) {
        if (this[index] == CR_BYTE && this[index + 1] == LF_BYTE) {
            return index
        }
        index += 1
    }
    return -1
}

private fun ByteArray.hasCrlfAt(index: Int): Boolean = index >= 0 && index < size - 1 && this[index] == CR_BYTE && this[index + 1] == LF_BYTE

private fun ByteArray.toAsciiStringOrNull(): String? {
    if (any { byte -> byte.toInt() !in ASCII_BYTE_RANGE }) {
        return null
    }
    return toString(Charsets.US_ASCII)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun ByteArray.decodeStrictUtf8(): String = try {
    Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: CharacterCodingException) {
    throw IllegalStateException("Forwarded response body is not valid UTF-8; use toByteArray() for byte-accurate rendering")
}

private const val CRLF = "\r\n"
private const val CR_BYTE = '\r'.code.toByte()
private const val LF_BYTE = '\n'.code.toByte()
private const val CONNECTION_HEADER = "connection"
private const val CONTENT_LENGTH_HEADER = "content-length"
private const val TRANSFER_ENCODING_HEADER = "transfer-encoding"
private const val CHUNKED_TRANSFER_CODING = "chunked"
private const val DELETE_CONTROL_CHAR = 0x7F
private const val NO_CONTENT_STATUS = 204
private const val NOT_MODIFIED_STATUS = 304
private const val HEX_RADIX = 16
private val HTTP_STATUS_CODE_RANGE = 100..599
private val INFORMATIONAL_STATUS_RANGE = 100..199
private val ASCII_BYTE_RANGE = 0x00..0x7F
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HOP_BY_HOP_RESPONSE_HEADERS =
    setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-connection",
        "te",
        "trailer",
        "upgrade",
    )
private val EMPTY_BODY = ByteArray(0)
private val HTTP_TOKEN_CHARS =
    (
        ('0'..'9') +
            ('A'..'Z') +
            ('a'..'z') +
            listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
    ).toSet()
