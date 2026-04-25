package com.cellularproxy.proxy.protocol

import java.nio.charset.StandardCharsets
import java.util.Locale

data class ParsedHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, List<String>>,
)

sealed interface HttpResponseHeaderBlockParseResult {
    data class Accepted(val response: ParsedHttpResponse) : HttpResponseHeaderBlockParseResult
    data class Rejected(val reason: HttpResponseHeaderBlockRejectionReason) : HttpResponseHeaderBlockParseResult
}

enum class HttpResponseHeaderBlockRejectionReason {
    MalformedStatusLine,
    MalformedHeader,
    ObsoleteLineFolding,
    IncompleteHeaderBlock,
    HeaderBlockTooLarge,
}

object HttpResponseHeaderBlockParser {
    fun parse(
        headerBlock: String,
        maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    ): HttpResponseHeaderBlockParseResult {
        require(maxHeaderBytes > 0) { "Maximum header bytes must be positive" }

        if (headerBlock.toByteArray(StandardCharsets.UTF_8).size > maxHeaderBytes) {
            return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.HeaderBlockTooLarge)
        }

        val headerTerminator = headerBlock.headerTerminator()
            ?: return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.IncompleteHeaderBlock)
        if (headerTerminator.endIndex != headerBlock.length) {
            return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader)
        }

        val headerText = headerBlock.substring(0, headerTerminator.startIndex)
        val lines = headerText.split(headerTerminator.lineSeparator)
        val status = parseStatusLine(lines.firstOrNull().orEmpty())
            ?: return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedStatusLine)

        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) {
                return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader)
            }
            if (line.first().isWhitespace()) {
                return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.ObsoleteLineFolding)
            }

            val separator = line.indexOf(':')
            if (separator <= 0) {
                return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader)
            }

            val name = line.substring(0, separator)
            if (!name.isHttpToken()) {
                return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader)
            }

            val value = line.substring(separator + 1).trimHorizontalWhitespace()
            if (value.any { it.isDisallowedHeaderValueControl() }) {
                return HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader)
            }

            headers.getOrPut(name.lowercase(Locale.US)) { mutableListOf() }.add(value)
        }

        return HttpResponseHeaderBlockParseResult.Accepted(
            ParsedHttpResponse(
                statusCode = status.statusCode,
                reasonPhrase = status.reasonPhrase,
                headers = headers.mapValues { (_, values) -> values.toList() },
            ),
        )
    }

    private fun parseStatusLine(statusLine: String): ParsedStatusLine? {
        if (statusLine.any(Char::isISOControl)) {
            return null
        }
        if (!statusLine.startsWith(SUPPORTED_HTTP_VERSION_PREFIX)) {
            return null
        }

        val codeStart = SUPPORTED_HTTP_VERSION_PREFIX.length
        val reasonSeparator = statusLine.indexOf(' ', startIndex = codeStart)
        if (reasonSeparator == -1) {
            return null
        }

        val statusCodeText = statusLine.substring(codeStart, reasonSeparator)
        if (statusCodeText.length != STATUS_CODE_LENGTH || !statusCodeText.all { it in '0'..'9' }) {
            return null
        }

        val statusCode = statusCodeText.toInt()
        if (statusCode !in HTTP_STATUS_CODE_RANGE) {
            return null
        }

        return ParsedStatusLine(
            statusCode = statusCode,
            reasonPhrase = statusLine.substring(reasonSeparator + 1),
        )
    }

    private fun String.headerTerminator(): ResponseHeaderTerminator? {
        val crlfIndex = indexOf(CRLF_TERMINATOR)
        val lfIndex = indexOf(LF_TERMINATOR)

        return when {
            crlfIndex == -1 && lfIndex == -1 -> null
            crlfIndex == -1 -> ResponseHeaderTerminator(lfIndex, LF)
            lfIndex == -1 -> ResponseHeaderTerminator(crlfIndex, CRLF)
            crlfIndex <= lfIndex -> ResponseHeaderTerminator(crlfIndex, CRLF)
            else -> ResponseHeaderTerminator(lfIndex, LF)
        }
    }

    private fun String.isHttpToken(): Boolean =
        isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

    private fun String.trimHorizontalWhitespace(): String =
        trim { it == ' ' || it == '\t' }

    private fun Char.isDisallowedHeaderValueControl(): Boolean =
        code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR
}

private data class ParsedStatusLine(
    val statusCode: Int,
    val reasonPhrase: String,
)

private data class ResponseHeaderTerminator(
    val startIndex: Int,
    val lineSeparator: String,
) {
    val endIndex: Int = startIndex + lineSeparator.length * HEADER_TERMINATOR_LINE_COUNT
}

private const val HEADER_TERMINATOR_LINE_COUNT = 2
private const val DEFAULT_MAX_HEADER_BYTES = 16 * 1024
private const val STATUS_CODE_LENGTH = 3
private const val CRLF = "\r\n"
private const val LF = "\n"
private const val CRLF_TERMINATOR = "\r\n\r\n"
private const val LF_TERMINATOR = "\n\n"
private const val SUPPORTED_HTTP_VERSION_PREFIX = "HTTP/1.1 "
private const val DELETE_CONTROL_CHAR = 0x7F
private val HTTP_STATUS_CODE_RANGE = 100..599
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_TOKEN_CHARS = (
    ('0'..'9') +
        ('A'..'Z') +
        ('a'..'z') +
        listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
).toSet()
