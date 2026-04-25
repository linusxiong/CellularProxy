package com.cellularproxy.proxy.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpResponseHeaderBlockParserTest {
    @Test
    fun `parses CRLF-terminated origin response headers`() {
        val rawHeaders =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n"

        val result = HttpResponseHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpResponseHeaderBlockParseResult.Accepted>(result).response
        assertEquals(200, parsed.statusCode)
        assertEquals("OK", parsed.reasonPhrase)
        assertEquals(listOf("5"), parsed.headers["content-length"])
        assertEquals(listOf("text/plain"), parsed.headers["content-type"])
    }

    @Test
    fun `parses LF-terminated origin responses with empty reason phrases`() {
        val rawHeaders = "HTTP/1.1 204 \nContent-Length: 0\n\n"

        val result = HttpResponseHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpResponseHeaderBlockParseResult.Accepted>(result).response
        assertEquals(204, parsed.statusCode)
        assertEquals("", parsed.reasonPhrase)
        assertEquals(listOf("0"), parsed.headers["content-length"])
    }

    @Test
    fun `preserves duplicate response headers case-insensitively`() {
        val rawHeaders =
            "HTTP/1.1 200 OK\r\n" +
                "Set-Cookie: first=1\r\n" +
                "set-cookie: second=2\r\n" +
                "\r\n"

        val result = HttpResponseHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpResponseHeaderBlockParseResult.Accepted>(result).response
        assertEquals(
            listOf("first=1", "second=2"),
            parsed.headers["set-cookie"],
        )
    }

    @Test
    fun `rejects malformed or unsupported response status lines`() {
        listOf(
            "HTTP/1.0 200 OK\r\n\r\n",
            "HTTP/1.1 OK\r\n\r\n",
            "HTTP/1.1 99 Almost\r\n\r\n",
            "HTTP/1.1 600 Too High\r\n\r\n",
            "HTTP/1.1 200 OK\u0000\r\n\r\n",
        ).forEach { rawHeaders ->
            assertEquals(
                HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedStatusLine),
                HttpResponseHeaderBlockParser.parse(rawHeaders),
            )
        }
    }

    @Test
    fun `rejects incomplete and oversized response header blocks`() {
        val rawHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n"

        assertEquals(
            HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.IncompleteHeaderBlock),
            HttpResponseHeaderBlockParser.parse(rawHeaders),
        )
        assertEquals(
            HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.HeaderBlockTooLarge),
            HttpResponseHeaderBlockParser.parse("$rawHeaders\r\n", maxHeaderBytes = rawHeaders.length),
        )
    }

    @Test
    fun `rejects malformed response headers`() {
        listOf(
            "HTTP/1.1 200 OK\r\nBad Header: value\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Test: safe\u0000unsafe\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Test: safe\u007funsafe\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Test: safe\tunsafe\r\n\r\n",
            "HTTP/1.1 200 OK\r\n\r\nbody",
        ).forEach { rawHeaders ->
            assertEquals(
                HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.MalformedHeader),
                HttpResponseHeaderBlockParser.parse(rawHeaders),
            )
        }
    }

    @Test
    fun `rejects obsolete folded response headers`() {
        val rawHeaders = "HTTP/1.1 200 OK\r\nX-Test: one\r\n continued\r\n\r\n"

        assertEquals(
            HttpResponseHeaderBlockParseResult.Rejected(HttpResponseHeaderBlockRejectionReason.ObsoleteLineFolding),
            HttpResponseHeaderBlockParser.parse(rawHeaders),
        )
    }
}
