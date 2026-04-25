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
    fun `strips standard hop-by-hop headers before forwarding to the origin server`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "origin.example",
                port = 80,
                originTarget = "/resource",
            ),
            headers = linkedMapOf(
                "connection" to listOf("keep-alive"),
                "keep-alive" to listOf("timeout=5"),
                "proxy-authenticate" to listOf("Basic realm=\"proxy\""),
                "te" to listOf("trailers"),
                "trailer" to listOf("x-checksum"),
                "upgrade" to listOf("websocket"),
                "accept" to listOf("application/json"),
            ),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request).toHttpString()

        assertContains(rendered, "GET /resource HTTP/1.1\r\n")
        assertContains(rendered, "host: origin.example\r\n")
        assertContains(rendered, "accept: application/json\r\n")
        assertEquals(false, rendered.contains("connection:", ignoreCase = true))
        assertEquals(false, rendered.contains("keep-alive:", ignoreCase = true))
        assertEquals(false, rendered.contains("proxy-authenticate:", ignoreCase = true))
        assertEquals(false, rendered.contains("te:", ignoreCase = true))
        assertEquals(false, rendered.contains("trailer:", ignoreCase = true))
        assertEquals(false, rendered.contains("upgrade:", ignoreCase = true))
    }

    @Test
    fun `rejects inbound transfer encoding because request body framing is unsupported`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "POST",
                host = "origin.example",
                port = 80,
                originTarget = "/submit",
            ),
            headers = linkedMapOf(
                "transfer-encoding" to listOf("chunked"),
                "content-type" to listOf("text/plain"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            HttpProxyForwardRequestRenderer.render(request)
        }
    }

    @Test
    fun `strips headers nominated by the connection header before forwarding`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "origin.example",
                port = 80,
                originTarget = "/resource",
            ),
            headers = linkedMapOf(
                "connection" to listOf("X-Hop, keep-alive", "X-Trace"),
                "x-hop" to listOf("remove-me"),
                "x-trace" to listOf("remove-too"),
                "x-end-to-end" to listOf("preserve-me"),
            ),
        )

        val rendered = HttpProxyForwardRequestRenderer.render(request).toHttpString()

        assertContains(rendered, "host: origin.example\r\n")
        assertContains(rendered, "x-end-to-end: preserve-me\r\n")
        assertEquals(false, rendered.contains("connection:", ignoreCase = true))
        assertEquals(false, rendered.contains("x-hop:", ignoreCase = true))
        assertEquals(false, rendered.contains("x-trace:", ignoreCase = true))
        assertEquals(false, rendered.contains("remove-me"))
        assertEquals(false, rendered.contains("remove-too"))
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
    fun `renders fixed-length request bodies after forwarded headers`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "POST",
                host = "origin.example",
                port = 80,
                originTarget = "/submit",
            ),
            headers = linkedMapOf(
                "content-length" to listOf("4"),
                "content-type" to listOf("text/plain"),
            ),
        )

        val forwarded = HttpProxyForwardRequestRenderer.render(
            request = request,
            body = "ping".toByteArray(Charsets.UTF_8),
        )

        assertEquals("POST /submit HTTP/1.1\r\n", forwarded.requestLine + "\r\n")
        assertEquals(
            "POST /submit HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "content-length: 4\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n" +
                "ping",
            forwarded.toHttpString(),
        )
        assertEquals(forwarded.toHttpString().toByteArray(Charsets.UTF_8).toList(), forwarded.toByteArray().toList())
    }

    @Test
    fun `rejects request bodies that do not match the forwarded content length`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "POST",
                host = "origin.example",
                port = 80,
                originTarget = "/submit",
            ),
            headers = linkedMapOf("content-length" to listOf("4")),
        )

        assertFailsWith<IllegalArgumentException> {
            HttpProxyForwardRequestRenderer.render(
                request = request,
                body = "toolong".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects request bodies without a fixed content length`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "POST",
                host = "origin.example",
                port = 80,
                originTarget = "/submit",
            ),
            headers = emptyMap(),
        )

        assertFailsWith<IllegalArgumentException> {
            HttpProxyForwardRequestRenderer.render(
                request = request,
                body = "ping".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects ambiguous forwarded content length headers`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpRequest(
                host = "example.com",
                port = 80,
                requestLine = "POST / HTTP/1.1",
                headers = linkedMapOf(
                    "Content-Length" to listOf("4"),
                    "content-length" to listOf("4"),
                ),
                body = "ping".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpRequest(
                host = "example.com",
                port = 80,
                requestLine = "POST / HTTP/1.1",
                headers = linkedMapOf("content-length" to listOf("4", "4")),
                body = "ping".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects malformed forwarded content length values`() {
        listOf("+4", "-1", "4 ", " 4", "0x4", "\u0664").forEach { contentLength ->
            assertFailsWith<IllegalArgumentException>("Expected $contentLength to be rejected") {
                ForwardedHttpRequest(
                    host = "example.com",
                    port = 80,
                    requestLine = "POST / HTTP/1.1",
                    headers = linkedMapOf("content-length" to listOf(contentLength)),
                    body = "ping".toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    @Test
    fun `defensively snapshots forwarded request bodies`() {
        val originalBody = "ping".toByteArray(Charsets.UTF_8)
        val forwarded = ForwardedHttpRequest(
            host = "example.com",
            port = 80,
            requestLine = "POST / HTTP/1.1",
            headers = linkedMapOf("content-length" to listOf("4")),
            body = originalBody,
        )

        originalBody[0] = 'd'.code.toByte()
        forwarded.body[1] = 'o'.code.toByte()

        assertEquals("ping", forwarded.body.toString(Charsets.UTF_8))
        assertEquals(
            "POST / HTTP/1.1\r\n" +
                "content-length: 4\r\n" +
                "\r\n" +
                "ping",
            forwarded.toHttpString(),
        )
    }

    @Test
    fun `byte rendering preserves non-utf8 request bodies and string rendering rejects them`() {
        val binaryBody = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val forwarded = ForwardedHttpRequest(
            host = "example.com",
            port = 80,
            requestLine = "POST /binary HTTP/1.1",
            headers = linkedMapOf("content-length" to listOf(binaryBody.size.toString())),
            body = binaryBody,
        )

        assertEquals(
            (
                "POST /binary HTTP/1.1\r\n" +
                    "content-length: 4\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8).toList() + binaryBody.toList(),
            forwarded.toByteArray().toList(),
        )
        assertFailsWith<IllegalStateException> {
            forwarded.toHttpString()
        }
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
