package com.cellularproxy.proxy.protocol

import com.cellularproxy.shared.management.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProxyRequestLineParserTest {
    @Test
    fun `parses absolute-form HTTP proxy request`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com:8080/path?q=1 HTTP/1.1")

        val request = assertIs<ProxyRequestLineParseResult.Accepted>(result).request
        val proxyRequest = assertIs<ParsedProxyRequest.HttpProxy>(request)
        assertEquals("GET", proxyRequest.method)
        assertEquals("example.com", proxyRequest.host)
        assertEquals(8080, proxyRequest.port)
        assertEquals("/path?q=1", proxyRequest.originTarget)
    }

    @Test
    fun `absolute-form HTTP proxy request defaults to port 80`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com/path HTTP/1.1")

        val request = assertIs<ProxyRequestLineParseResult.Accepted>(result).request
        val proxyRequest = assertIs<ParsedProxyRequest.HttpProxy>(request)
        assertEquals(80, proxyRequest.port)
    }

    @Test
    fun `rejects absolute-form HTTP proxy requests with invalid ports`() {
        val invalidTargets = listOf(
            "http://example.com:0/",
            "http://example.com:65536/",
        )

        invalidTargets.forEach { target ->
            assertEquals(
                ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri),
                ProxyRequestLineParser.parse("GET $target HTTP/1.1"),
                "Expected $target to be rejected",
            )
        }
    }

    @Test
    fun `rejects absolute-form HTTP proxy requests with userinfo`() {
        val result = ProxyRequestLineParser.parse("GET http://trusted.example@127.0.0.1/path HTTP/1.1")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri),
            result,
        )
    }

    @Test
    fun `parses CONNECT authority-form tunnel request`() {
        val result = ProxyRequestLineParser.parse("CONNECT example.com:443 HTTP/1.1")

        val request = assertIs<ProxyRequestLineParseResult.Accepted>(result).request
        val connectRequest = assertIs<ParsedProxyRequest.ConnectTunnel>(request)
        assertEquals("example.com", connectRequest.host)
        assertEquals(443, connectRequest.port)
    }

    @Test
    fun `parses CONNECT authority-form IPv6 tunnel request`() {
        val result = ProxyRequestLineParser.parse("CONNECT [2001:db8::1]:443 HTTP/1.1")

        val request = assertIs<ProxyRequestLineParseResult.Accepted>(result).request
        val connectRequest = assertIs<ParsedProxyRequest.ConnectTunnel>(request)
        assertEquals("[2001:db8::1]", connectRequest.host)
        assertEquals(443, connectRequest.port)
    }

    @Test
    fun `parses supported management origin-form request`() {
        val result = ProxyRequestLineParser.parse("POST /api/service/stop HTTP/1.1")

        val request = assertIs<ProxyRequestLineParseResult.Accepted>(result).request
        val managementRequest = assertIs<ParsedProxyRequest.Management>(request)
        assertEquals(HttpMethod.Post, managementRequest.method)
        assertEquals("/api/service/stop", managementRequest.originTarget)
        assertEquals(true, managementRequest.requiresToken)
        assertEquals(true, managementRequest.requiresAuditLog)
    }

    @Test
    fun `rejects unsupported HTTP versions`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com/ HTTP/1.0")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedHttpVersion),
            result,
        )
    }

    @Test
    fun `rejects absolute URI fragments`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com/path#fragment HTTP/1.1")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri),
            result,
        )
    }

    @Test
    fun `rejects unsupported proxy schemes`() {
        val result = ProxyRequestLineParser.parse("GET ftp://example.com/file HTTP/1.1")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedProxyScheme),
            result,
        )
    }

    @Test
    fun `rejects malformed CONNECT authorities`() {
        val invalidTargets = listOf(
            "example.com",
            "example.com:0",
            "example.com:65536",
            "user@example.com:443",
            "[2001:db8::1]",
        )

        invalidTargets.forEach { target ->
            assertEquals(
                ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidConnectAuthority),
                ProxyRequestLineParser.parse("CONNECT $target HTTP/1.1"),
                "Expected $target to be rejected",
            )
        }
    }

    @Test
    fun `rejects unsupported origin-form targets`() {
        val result = ProxyRequestLineParser.parse("GET /ordinary/path HTTP/1.1")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedOriginFormTarget),
            result,
        )
    }

    @Test
    fun `rejects unsupported management methods`() {
        val result = ProxyRequestLineParser.parse("PUT /api/status HTTP/1.1")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedManagementMethod),
            result,
        )
    }

    @Test
    fun `rejects malformed request lines with extra fields`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com/ HTTP/1.1 extra")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.MalformedRequestLine),
            result,
        )
    }

    @Test
    fun `rejects request lines containing control characters`() {
        val result = ProxyRequestLineParser.parse("GET http://example.com/ HTTP/1.1\r")

        assertEquals(
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.MalformedRequestLine),
            result,
        )
    }
}
