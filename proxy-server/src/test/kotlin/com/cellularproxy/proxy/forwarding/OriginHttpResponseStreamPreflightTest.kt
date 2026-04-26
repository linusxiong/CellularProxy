package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpResponseHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class OriginHttpResponseStreamPreflightTest {
    @Test
    fun `accepts parsed origin response headers and leaves body bytes unread`() {
        val headerBlock = "HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\n"
        val headerBytes = headerBlock.toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(headerBytes + "body".toByteArray(Charsets.UTF_8))

        val result = OriginHttpResponseStreamPreflight.evaluate(input)

        assertEquals(
            OriginHttpResponseStreamPreflightResult.Accepted(
                response = ParsedHttpResponse(
                    statusCode = 200,
                    reasonPhrase = "OK",
                    headers = mapOf("content-length" to listOf("4")),
                ),
                headerBytesRead = headerBytes.size,
            ),
            result,
        )
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `rejects incomplete origin response headers with byte count`() {
        val bytes = "HTTP/1.1 200 OK\r\ncontent-length: 4\r\n".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bytes)

        val result = OriginHttpResponseStreamPreflight.evaluate(input)

        assertEquals(
            OriginHttpResponseStreamPreflightResult.Rejected(
                reason = OriginHttpResponseStreamPreflightRejectionReason.IncompleteHeaderBlock,
                headerBytesRead = bytes.size,
            ),
            result,
        )
    }

    @Test
    fun `rejects oversized origin response headers without reading past byte limit`() {
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\nx: y\r\n\r\nbody".toByteArray(Charsets.UTF_8))

        val result = OriginHttpResponseStreamPreflight.evaluate(
            input = input,
            maxHeaderBytes = 12,
        )

        assertEquals(
            OriginHttpResponseStreamPreflightResult.Rejected(
                reason = OriginHttpResponseStreamPreflightRejectionReason.HeaderBlockTooLarge,
                headerBytesRead = 12,
            ),
            result,
        )
        assertEquals(' '.code, input.read())
    }

    @Test
    fun `accepted result diagnostics do not include origin-controlled response data`() {
        val result = OriginHttpResponseStreamPreflightResult.Accepted(
            response = ParsedHttpResponse(
                statusCode = 302,
                reasonPhrase = "Redirect contains origin text",
                headers = mapOf(
                    "set-cookie" to listOf("session=secret"),
                    "location" to listOf("https://origin.example/sensitive"),
                ),
            ),
            headerBytesRead = 96,
        )

        val diagnostic = result.toString()

        assertEquals(
            "Accepted(statusCode=302, headerCount=2, headerBytesRead=96)",
            diagnostic,
        )
    }

    @Test
    fun `rejects malformed UTF-8 origin response headers without exposing raw bytes`() {
        val input = ByteArrayInputStream(
            byteArrayOf(
                'H'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                'P'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                '.'.code.toByte(),
                '1'.code.toByte(),
                ' '.code.toByte(),
                '2'.code.toByte(),
                '0'.code.toByte(),
                '0'.code.toByte(),
                ' '.code.toByte(),
                0xC3.toByte(),
                0x28.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                'b'.code.toByte(),
            ),
        )

        val result = OriginHttpResponseStreamPreflight.evaluate(input)

        assertEquals(
            OriginHttpResponseStreamPreflightResult.Rejected(
                reason = OriginHttpResponseStreamPreflightRejectionReason.MalformedHeaderEncoding,
                headerBytesRead = 19,
            ),
            result,
        )
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `surfaces origin response header parser rejections`() {
        val headerBlock = "HTTP/1.1 999 Nope\r\n\r\n"
        val input = ByteArrayInputStream(headerBlock.toByteArray(Charsets.UTF_8))

        val result = OriginHttpResponseStreamPreflight.evaluate(input)

        assertEquals(
            OriginHttpResponseStreamPreflightResult.Rejected(
                reason = OriginHttpResponseStreamPreflightRejectionReason.HeaderParseRejected(
                    HttpResponseHeaderBlockRejectionReason.MalformedStatusLine,
                ),
                headerBytesRead = headerBlock.toByteArray(Charsets.UTF_8).size,
            ),
            result,
        )
    }
}
