package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpHeaderBlockStreamReadResult
import com.cellularproxy.proxy.protocol.HttpHeaderBlockStreamReader
import com.cellularproxy.proxy.protocol.HttpResponseHeaderBlockParseResult
import com.cellularproxy.proxy.protocol.HttpResponseHeaderBlockParser
import com.cellularproxy.proxy.protocol.HttpResponseHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import java.io.InputStream

sealed interface OriginHttpResponseStreamPreflightResult {
    data class Accepted(
        val response: ParsedHttpResponse,
        val headerBytesRead: Int,
    ) : OriginHttpResponseStreamPreflightResult {
        init {
            require(headerBytesRead > 0) { "Header bytes read must be positive" }
        }

        override fun toString(): String =
            "Accepted(statusCode=${response.statusCode}, " +
                "headerCount=${response.headers.size}, " +
                "headerBytesRead=$headerBytesRead)"
    }

    data class Rejected(
        val reason: OriginHttpResponseStreamPreflightRejectionReason,
        val headerBytesRead: Int,
    ) : OriginHttpResponseStreamPreflightResult {
        init {
            require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        }
    }
}

sealed interface OriginHttpResponseStreamPreflightRejectionReason {
    data object IncompleteHeaderBlock : OriginHttpResponseStreamPreflightRejectionReason

    data object HeaderBlockTooLarge : OriginHttpResponseStreamPreflightRejectionReason

    data object MalformedHeaderEncoding : OriginHttpResponseStreamPreflightRejectionReason

    data class HeaderParseRejected(
        val reason: HttpResponseHeaderBlockRejectionReason,
    ) : OriginHttpResponseStreamPreflightRejectionReason
}

object OriginHttpResponseStreamPreflight {
    fun evaluate(
        input: InputStream,
        maxHeaderBytes: Int = DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES,
    ): OriginHttpResponseStreamPreflightResult {
        require(maxHeaderBytes > 0) { "Maximum header bytes must be positive" }

        return when (
            val readResult =
                HttpHeaderBlockStreamReader.read(
                    input = input,
                    maxHeaderBytes = maxHeaderBytes,
                )
        ) {
            is HttpHeaderBlockStreamReadResult.Completed ->
                parseHeaderBlock(
                    headerBlock = readResult.headerBlock,
                    headerBytesRead = readResult.bytesRead,
                    maxHeaderBytes = maxHeaderBytes,
                )
            is HttpHeaderBlockStreamReadResult.Incomplete ->
                OriginHttpResponseStreamPreflightResult.Rejected(
                    reason = OriginHttpResponseStreamPreflightRejectionReason.IncompleteHeaderBlock,
                    headerBytesRead = readResult.bytesRead,
                )
            is HttpHeaderBlockStreamReadResult.HeaderBlockTooLarge ->
                OriginHttpResponseStreamPreflightResult.Rejected(
                    reason = OriginHttpResponseStreamPreflightRejectionReason.HeaderBlockTooLarge,
                    headerBytesRead = readResult.bytesRead,
                )
            is HttpHeaderBlockStreamReadResult.MalformedHeaderEncoding ->
                OriginHttpResponseStreamPreflightResult.Rejected(
                    reason = OriginHttpResponseStreamPreflightRejectionReason.MalformedHeaderEncoding,
                    headerBytesRead = readResult.bytesRead,
                )
        }
    }

    private fun parseHeaderBlock(
        headerBlock: String,
        headerBytesRead: Int,
        maxHeaderBytes: Int,
    ): OriginHttpResponseStreamPreflightResult =
        when (
            val parseResult =
                HttpResponseHeaderBlockParser.parse(
                    headerBlock = headerBlock,
                    maxHeaderBytes = maxHeaderBytes,
                )
        ) {
            is HttpResponseHeaderBlockParseResult.Accepted ->
                OriginHttpResponseStreamPreflightResult.Accepted(
                    response = parseResult.response,
                    headerBytesRead = headerBytesRead,
                )
            is HttpResponseHeaderBlockParseResult.Rejected ->
                OriginHttpResponseStreamPreflightResult.Rejected(
                    reason = OriginHttpResponseStreamPreflightRejectionReason.HeaderParseRejected(parseResult.reason),
                    headerBytesRead = headerBytesRead,
                )
        }
}

private const val DEFAULT_ORIGIN_RESPONSE_MAX_HEADER_BYTES = 16 * 1024
