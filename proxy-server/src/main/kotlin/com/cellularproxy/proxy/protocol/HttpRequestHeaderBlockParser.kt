package com.cellularproxy.proxy.protocol

import java.nio.charset.StandardCharsets
import java.util.Locale

data class ParsedHttpRequest(
    val request: ParsedProxyRequest,
    val headers: Map<String, List<String>>,
)

sealed interface HttpRequestHeaderBlockParseResult {
    data class Accepted(val request: ParsedHttpRequest) : HttpRequestHeaderBlockParseResult

    data class Rejected(
        val reason: HttpRequestHeaderBlockRejectionReason,
        val requestLineRejectionReason: ProxyRequestLineRejectionReason? = null,
    ) : HttpRequestHeaderBlockParseResult {
        init {
            require((reason == HttpRequestHeaderBlockRejectionReason.RequestLineRejected) == (requestLineRejectionReason != null)) {
                "Request-line rejection details are required only for request-line rejections"
            }
        }
    }
}

enum class HttpRequestHeaderBlockRejectionReason {
    RequestLineRejected,
    MalformedHeader,
    MalformedHeaderEncoding,
    ObsoleteLineFolding,
    IncompleteHeaderBlock,
    HeaderBlockTooLarge,
}

object HttpRequestHeaderBlockParser {
    fun parse(
        headerBlock: String,
        maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    ): HttpRequestHeaderBlockParseResult {
        require(maxHeaderBytes > 0) { "Maximum header bytes must be positive" }

        if (headerBlock.toByteArray(StandardCharsets.UTF_8).size > maxHeaderBytes) {
            return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.HeaderBlockTooLarge)
        }

        val headerTerminator = headerBlock.headerTerminator()
            ?: return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.IncompleteHeaderBlock)
        if (headerTerminator.endIndex != headerBlock.length) {
            return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader)
        }

        val headerText = headerBlock.substring(0, headerTerminator.startIndex)
        val lines = headerText.split(headerTerminator.lineSeparator)
        if (lines.isEmpty()) {
            return HttpRequestHeaderBlockParseResult.Rejected(
                reason = HttpRequestHeaderBlockRejectionReason.RequestLineRejected,
                requestLineRejectionReason = ProxyRequestLineRejectionReason.MalformedRequestLine,
            )
        }

        val request = when (val requestLine = ProxyRequestLineParser.parse(lines.first())) {
            is ProxyRequestLineParseResult.Accepted -> requestLine.request
            is ProxyRequestLineParseResult.Rejected ->
                return HttpRequestHeaderBlockParseResult.Rejected(
                    reason = HttpRequestHeaderBlockRejectionReason.RequestLineRejected,
                    requestLineRejectionReason = requestLine.reason,
                )
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) {
                return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader)
            }
            if (line.first().isWhitespace()) {
                return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.ObsoleteLineFolding)
            }

            val separator = line.indexOf(':')
            if (separator <= 0) {
                return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader)
            }

            val name = line.substring(0, separator)
            if (!name.isHttpToken()) {
                return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader)
            }

            val value = line.substring(separator + 1).trimHorizontalWhitespace()
            if (value.any { it.isDisallowedHeaderValueControl() }) {
                return HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader)
            }

            headers.getOrPut(name.lowercase(Locale.US)) { mutableListOf() }.add(value)
        }

        return HttpRequestHeaderBlockParseResult.Accepted(
            ParsedHttpRequest(
                request = request,
                headers = headers.mapValues { (_, values) -> values.toList() },
            ),
        )
    }

    private fun String.headerTerminator(): HeaderTerminator? {
        val crlfIndex = indexOf(CRLF_TERMINATOR)
        val lfIndex = indexOf(LF_TERMINATOR)

        return when {
            crlfIndex == -1 && lfIndex == -1 -> null
            crlfIndex == -1 -> HeaderTerminator(lfIndex, LF)
            lfIndex == -1 -> HeaderTerminator(crlfIndex, CRLF)
            crlfIndex <= lfIndex -> HeaderTerminator(crlfIndex, CRLF)
            else -> HeaderTerminator(lfIndex, LF)
        }
    }

    private fun String.isHttpToken(): Boolean =
        isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

    private fun String.trimHorizontalWhitespace(): String =
        trim { it == ' ' || it == '\t' }

    private fun Char.isDisallowedHeaderValueControl(): Boolean =
        code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR
}

private data class HeaderTerminator(
    val startIndex: Int,
    val lineSeparator: String,
) {
    val endIndex: Int = startIndex + lineSeparator.length * HEADER_TERMINATOR_LINE_COUNT
}

private const val HEADER_TERMINATOR_LINE_COUNT = 2
private const val DEFAULT_MAX_HEADER_BYTES = 16 * 1024
private const val CRLF = "\r\n"
private const val LF = "\n"
private const val CRLF_TERMINATOR = "\r\n\r\n"
private const val LF_TERMINATOR = "\n\n"
private const val DELETE_CONTROL_CHAR = 0x7F
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_TOKEN_CHARS = (
    ('0'..'9') +
        ('A'..'Z') +
        ('a'..'z') +
        listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
).toSet()
