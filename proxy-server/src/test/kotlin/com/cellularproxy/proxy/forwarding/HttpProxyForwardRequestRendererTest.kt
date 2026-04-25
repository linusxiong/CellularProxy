package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpProxyForwardRequestRendererTest {
    @Test
    fun `renders absolute-form proxy request as origin-form outbound request`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "example.com",
                port = 8080,
                originTarget = "/search?q=test",
            ),
            headers = linkedMapOf(
                "host" to listOf("example.com"),
                "accept" to listOf("text/plain"),
            ),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request)

        assertEquals("example.com", rendered.host)
        assertEquals(8080, rendered.port)
        assertEquals(
            "GET /search?q=test HTTP/1.1\r\n" +
                "host: example.com:8080\r\n" +
                "accept: text/plain\r\n" +
                "\r\n",
            rendered.toHttpString(),
        )
        assertEquals(rendered.toHttpString().toByteArray(Charsets.UTF_8).toList(), rendered.toByteArray().toList())
    }

    @Test
    fun `strips proxy-only headers before forwarding to the origin server`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "POST",
                host = "origin.example",
                port = 80,
                originTarget = "/submit",
            ),
            headers = linkedMapOf(
                "host" to listOf("origin.example"),
                "proxy-authorization" to listOf("Basic secret"),
                "proxy-connection" to listOf("keep-alive"),
                "x-request-id" to listOf("safe-id"),
            ),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request).toHttpString()

        assertContains(rendered, "POST /submit HTTP/1.1\r\n")
        assertContains(rendered, "host: origin.example\r\n")
        assertContains(rendered, "x-request-id: safe-id\r\n")
        assertEquals(false, rendered.contains("proxy-authorization", ignoreCase = true))
        assertEquals(false, rendered.contains("proxy-connection", ignoreCase = true))
        assertEquals(false, rendered.contains("secret"))
    }

    @Test
    fun `derives host header from parsed absolute-form target instead of client supplied headers`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "origin.example",
                port = 8080,
                originTarget = "/resource",
            ),
            headers = linkedMapOf(
                "host" to listOf("attacker.example"),
                "accept" to listOf("application/json"),
            ),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request).toHttpString()

        assertContains(rendered, "GET /resource HTTP/1.1\r\n")
        assertContains(rendered, "host: origin.example:8080\r\n")
        assertContains(rendered, "accept: application/json\r\n")
        assertEquals(false, rendered.contains("attacker.example"))
    }

    @Test
    fun `adds host header from parsed target when inbound request omitted it`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "origin.example",
                port = 80,
                originTarget = "/",
            ),
            headers = linkedMapOf("accept" to listOf("*/*")),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request).toHttpString()

        assertEquals(
            "GET / HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "accept: */*\r\n" +
                "\r\n",
            rendered,
        )
    }

    @Test
    fun `rejects connect and management requests because they are not plain http forward requests`() {
        assertFailsWith<IllegalArgumentException> {
            HttpProxyForwardRequestRenderer.render(
                ParsedHttpRequest(
                    request = ParsedProxyRequest.ConnectTunnel("example.com", 443),
                    headers = emptyMap(),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HttpProxyForwardRequestRenderer.render(
                ParsedHttpRequest(
                    request = ParsedProxyRequest.Management(
                        method = com.cellularproxy.shared.management.HttpMethod.Get,
                        originTarget = "/health",
                        requiresToken = false,
                        requiresAuditLog = false,
                    ),
                    headers = emptyMap(),
                ),
            )
        }
    }

    @Test
    fun `rejects unsafe public construction inputs that could split outbound requests`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpRequest(
                host = "example.com",
                port = 80,
                requestLine = "GET / HTTP/1.1\r\nInjected: secret",
                headers = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpRequest(
                host = "example.com",
                port = 80,
                requestLine = "GET / HTTP/1.1",
                headers = mapOf("x-test\r\ninjected" to listOf("value")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpRequest(
                host = "example.com",
                port = 80,
                requestLine = "GET / HTTP/1.1",
                headers = mapOf("x-test" to listOf("safe\r\nInjected: secret")),
            )
        }
    }
}
