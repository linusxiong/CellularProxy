package com.cellularproxy.proxy.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpRequestHeaderBlockParserTest {
    @Test
    fun `parses CRLF-terminated request headers`() {
        val rawHeaders =
            "GET http://example.com/path HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Proxy-Authorization: Basic abc123\r\n" +
                "\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpRequestHeaderBlockParseResult.Accepted>(result).request
        val proxyRequest = assertIs<ParsedProxyRequest.HttpProxy>(parsed.request)
        assertEquals("example.com", proxyRequest.host)
        assertEquals("/path", proxyRequest.originTarget)
        assertEquals(listOf("example.com"), parsed.headers["host"])
        assertEquals(listOf("Basic abc123"), parsed.headers["proxy-authorization"])
    }

    @Test
    fun `parses LF-terminated request headers`() {
        val rawHeaders = "GET /health HTTP/1.1\nHost: phone.local\n\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpRequestHeaderBlockParseResult.Accepted>(result).request
        val managementRequest = assertIs<ParsedProxyRequest.Management>(parsed.request)
        assertEquals("/health", managementRequest.originTarget)
        assertEquals(listOf("phone.local"), parsed.headers["host"])
    }

    @Test
    fun `preserves duplicate headers case-insensitively`() {
        val rawHeaders =
            "CONNECT example.com:443 HTTP/1.1\r\n" +
                "Proxy-Authorization: Basic first\r\n" +
                "proxy-authorization: Basic second\r\n" +
                "\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        val parsed = assertIs<HttpRequestHeaderBlockParseResult.Accepted>(result).request
        assertEquals(
            listOf("Basic first", "Basic second"),
            parsed.headers["proxy-authorization"],
        )
    }

    @Test
    fun `propagates request-line rejection reason`() {
        val rawHeaders = "GET ftp://example.com/file HTTP/1.1\r\nHost: example.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(
                reason = HttpRequestHeaderBlockRejectionReason.RequestLineRejected,
                requestLineRejectionReason = ProxyRequestLineRejectionReason.UnsupportedProxyScheme,
            ),
            result,
        )
    }

    @Test
    fun `rejects incomplete header blocks`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.IncompleteHeaderBlock),
            result,
        )
    }

    @Test
    fun `rejects oversized header blocks`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders, maxHeaderBytes = rawHeaders.length - 1)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.HeaderBlockTooLarge),
            result,
        )
    }

    @Test
    fun `rejects malformed header field names`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nBad Header: value\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader),
            result,
        )
    }

    @Test
    fun `rejects header values with raw control characters`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example\u0000.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader),
            result,
        )
    }

    @Test
    fun `rejects header values with DEL control character`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example\u007f.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader),
            result,
        )
    }

    @Test
    fun `rejects header values with internal tab control character`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example\t.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader),
            result,
        )
    }

    @Test
    fun `propagates empty request-line rejection`() {
        val rawHeaders = "\r\nHost: example.com\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(
                reason = HttpRequestHeaderBlockRejectionReason.RequestLineRejected,
                requestLineRejectionReason = ProxyRequestLineRejectionReason.MalformedRequestLine,
            ),
            result,
        )
    }

    @Test
    fun `rejects obsolete folded headers`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n continued\r\n\r\n"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.ObsoleteLineFolding),
            result,
        )
    }

    @Test
    fun `rejects data after the header terminator`() {
        val rawHeaders = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\nignored"

        val result = HttpRequestHeaderBlockParser.parse(rawHeaders)

        assertEquals(
            HttpRequestHeaderBlockParseResult.Rejected(HttpRequestHeaderBlockRejectionReason.MalformedHeader),
            result,
        )
    }
}
