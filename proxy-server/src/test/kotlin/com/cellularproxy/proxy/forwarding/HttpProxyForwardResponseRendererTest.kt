package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpProxyForwardResponseRendererTest {
    @Test
    fun `renders sanitized origin response head without buffering the response body`() {
        val head = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf(
                    "connection" to listOf("X-Hop"),
                    "x-hop" to listOf("remove-me"),
                    "content-length" to listOf("5"),
                    "content-type" to listOf("text/plain"),
                ),
            ),
        )

        assertEquals(200, head.statusCode)
        assertEquals("OK", head.reasonPhrase)
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 5\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n",
            head.toHttpString(),
        )
        assertEquals(head.toHttpString().toByteArray(Charsets.UTF_8).toList(), head.toByteArray().toList())
    }

    @Test
    fun `renders close-delimited origin response head without requiring body framing headers`() {
        val head = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-type" to listOf("text/plain")),
            ),
        )

        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n",
            head.toHttpString(),
        )
    }

    @Test
    fun `strips response framing headers from no-body status heads except not-modified content length metadata`() {
        val noContentHead = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 204,
                reasonPhrase = "No Content",
                headers = linkedMapOf(
                    "content-length" to listOf("123"),
                    "transfer-encoding" to listOf("chunked"),
                    "x-safe" to listOf("keep"),
                ),
            ),
        )
        val informationalHead = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 101,
                reasonPhrase = "Switching Protocols",
                headers = linkedMapOf(
                    "content-length" to listOf("123"),
                    "x-safe" to listOf("keep"),
                ),
            ),
        )
        val notModifiedHead = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 304,
                reasonPhrase = "Not Modified",
                headers = linkedMapOf(
                    "content-length" to listOf("123"),
                    "transfer-encoding" to listOf("chunked"),
                    "x-safe" to listOf("keep"),
                ),
            ),
        )

        assertEquals(
            "HTTP/1.1 204 No Content\r\n" +
                "x-safe: keep\r\n" +
                "\r\n",
            noContentHead.toHttpString(),
        )
        assertEquals(
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "x-safe: keep\r\n" +
                "\r\n",
            informationalHead.toHttpString(),
        )
        assertEquals(
            "HTTP/1.1 304 Not Modified\r\n" +
                "content-length: 123\r\n" +
                "x-safe: keep\r\n" +
                "\r\n",
            notModifiedHead.toHttpString(),
        )
    }

    @Test
    fun `strips content length from transfer encoded response heads`() {
        val head = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf(
                    "transfer-encoding" to listOf("chunked"),
                    "content-length" to listOf("999"),
                    "content-type" to listOf("text/plain"),
                ),
            ),
        )

        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "transfer-encoding: chunked\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n",
            head.toHttpString(),
        )
    }

    @Test
    fun `normalizes identical duplicate content length values in response heads`() {
        val head = HttpProxyForwardResponseRenderer.renderHead(
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf(
                    "content-length" to listOf("7", "7"),
                    "content-type" to listOf("text/plain"),
                ),
            ),
        )

        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 7\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n",
            head.toHttpString(),
        )
    }

    @Test
    fun `rejects response head construction with unstreamable body framing metadata`() {
        val tooLargeContentLength = "9".repeat(20)

        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("content-length" to listOf(tooLargeContentLength)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 204,
                reasonPhrase = "No Content",
                headers = mapOf("content-length" to listOf("1")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 204,
                reasonPhrase = "No Content",
                headers = mapOf("transfer-encoding" to listOf("chunked")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf(
                    "transfer-encoding" to listOf("chunked"),
                    "content-length" to listOf("5"),
                ),
            )
        }
    }

    @Test
    fun `defensively snapshots forwarded response heads`() {
        val originalHeaders = linkedMapOf("content-length" to mutableListOf("2"))
        val head = ForwardedHttpResponseHead(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = originalHeaders,
        )

        originalHeaders["content-length"]?.set(0, "4")

        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 2\r\n" +
                "\r\n",
            head.toHttpString(),
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (head.headers as MutableMap<String, List<String>>)["x-test"] = listOf("value")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (head.headers.getValue("content-length") as MutableList<String>)[0] = "4"
        }
    }

    @Test
    fun `rejects unsafe public response head construction`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 200,
                reasonPhrase = "OK\r\nInjected: secret",
                headers = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("x-test\r\ninjected" to listOf("value")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponseHead(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("x-test" to listOf("safe\r\nInjected: secret")),
            )
        }
    }

    @Test
    fun `renders fixed-length origin response to the proxy client`() {
        val response = HttpProxyForwardResponseRenderer.render(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf(
                "content-length" to listOf("5"),
                "content-type" to listOf("text/plain"),
            ),
            body = "hello".toByteArray(Charsets.UTF_8),
        )

        assertEquals(200, response.statusCode)
        assertEquals("OK", response.reasonPhrase)
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 5\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n" +
                "hello",
            response.toHttpString(),
        )
        assertEquals(response.toHttpString().toByteArray(Charsets.UTF_8).toList(), response.toByteArray().toList())
    }

    @Test
    fun `strips hop-by-hop response headers before forwarding to the proxy client`() {
        val response = HttpProxyForwardResponseRenderer.render(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf(
                "connection" to listOf("keep-alive"),
                "keep-alive" to listOf("timeout=5"),
                "proxy-authenticate" to listOf("Basic realm=\"origin\""),
                "proxy-connection" to listOf("keep-alive"),
                "te" to listOf("trailers"),
                "trailer" to listOf("x-checksum"),
                "upgrade" to listOf("websocket"),
                "content-length" to listOf("2"),
                "x-end-to-end" to listOf("preserve-me"),
            ),
            body = "ok".toByteArray(Charsets.UTF_8),
        )

        val rendered = response.toHttpString()

        assertContains(rendered, "HTTP/1.1 200 OK\r\n")
        assertContains(rendered, "content-length: 2\r\n")
        assertContains(rendered, "x-end-to-end: preserve-me\r\n")
        assertEquals(false, rendered.contains("connection:", ignoreCase = true))
        assertEquals(false, rendered.contains("keep-alive:", ignoreCase = true))
        assertEquals(false, rendered.contains("proxy-authenticate:", ignoreCase = true))
        assertEquals(false, rendered.contains("proxy-connection:", ignoreCase = true))
        assertEquals(false, rendered.contains("te:", ignoreCase = true))
        assertEquals(false, rendered.contains("trailer:", ignoreCase = true))
        assertEquals(false, rendered.contains("upgrade:", ignoreCase = true))
    }

    @Test
    fun `strips headers nominated by the connection response header`() {
        val response = HttpProxyForwardResponseRenderer.render(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf(
                "connection" to listOf("X-Hop, keep-alive", "X-Trace"),
                "x-hop" to listOf("remove-me"),
                "x-trace" to listOf("remove-too"),
                "x-end-to-end" to listOf("preserve-me"),
                "content-length" to listOf("2"),
            ),
            body = "ok".toByteArray(Charsets.UTF_8),
        )

        val rendered = response.toHttpString()

        assertContains(rendered, "x-end-to-end: preserve-me\r\n")
        assertEquals(false, rendered.contains("connection:", ignoreCase = true))
        assertEquals(false, rendered.contains("x-hop:", ignoreCase = true))
        assertEquals(false, rendered.contains("x-trace:", ignoreCase = true))
        assertEquals(false, rendered.contains("remove-me"))
        assertEquals(false, rendered.contains("remove-too"))
    }

    @Test
    fun `preserves transfer-encoded response framing and byte body`() {
        val chunkedBody = "5\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)

        val response = HttpProxyForwardResponseRenderer.render(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf(
                "transfer-encoding" to listOf("chunked"),
                "content-type" to listOf("text/plain"),
            ),
            body = chunkedBody,
        )

        assertEquals(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "transfer-encoding: chunked\r\n" +
                    "content-type: text/plain\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8).toList() + chunkedBody.toList(),
            response.toByteArray().toList(),
        )
    }

    @Test
    fun `rejects unsupported or malformed transfer-encoded response framing`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("transfer-encoding" to listOf("gzip")),
                body = "hello".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("transfer-encoding" to listOf("gzip, chunked")),
                body = "5\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("transfer-encoding" to listOf("chunked")),
                body = "hello".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("transfer-encoding" to listOf("chunked")),
                body = "5\r\nhello\r\n".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects non-empty bodies for response statuses that cannot contain bodies`() {
        listOf(100, 101, 204, 304).forEach { statusCode ->
            assertFailsWith<IllegalArgumentException>("Expected $statusCode body to be rejected") {
                ForwardedHttpResponse(
                    statusCode = statusCode,
                    reasonPhrase = "No Body",
                    headers = linkedMapOf("content-length" to listOf("2")),
                    body = "ok".toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    @Test
    fun `rejects content length metadata for full responses with no-body statuses except not-modified`() {
        listOf(100, 101, 204).forEach { statusCode ->
            assertFailsWith<IllegalArgumentException>("Expected $statusCode Content-Length to be rejected") {
                ForwardedHttpResponse(
                    statusCode = statusCode,
                    reasonPhrase = "No Body",
                    headers = linkedMapOf("content-length" to listOf("0")),
                    body = ByteArray(0),
                )
            }
        }
    }

    @Test
    fun `allows empty not modified responses to preserve origin content length metadata`() {
        val response = ForwardedHttpResponse(
            statusCode = 304,
            reasonPhrase = "Not Modified",
            headers = linkedMapOf("content-length" to listOf("123")),
            body = ByteArray(0),
        )

        assertEquals(
            "HTTP/1.1 304 Not Modified\r\n" +
                "content-length: 123\r\n" +
                "\r\n",
            response.toHttpString(),
        )
    }

    @Test
    fun `rejects oversized not modified content length metadata`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 304,
                reasonPhrase = "Not Modified",
                headers = linkedMapOf("content-length" to listOf("9".repeat(20))),
                body = ByteArray(0),
            )
        }
    }

    @Test
    fun `allows empty reason phrases from origin responses`() {
        val response = ForwardedHttpResponse(
            statusCode = 200,
            reasonPhrase = "",
            headers = linkedMapOf("content-length" to listOf("2")),
            body = "ok".toByteArray(Charsets.UTF_8),
        )

        assertEquals(
            "HTTP/1.1 200 \r\n" +
                "content-length: 2\r\n" +
                "\r\n" +
                "ok",
            response.toHttpString(),
        )
    }

    @Test
    fun `rejects ambiguous or invalid response body framing`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("2")),
                body = "toolong".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("2", "2")),
                body = "ok".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("+2")),
                body = "ok".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf(
                    "content-length" to listOf("2"),
                    "transfer-encoding" to listOf("chunked"),
                ),
                body = "ok".toByteArray(Charsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = emptyMap(),
                body = "ok".toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `defensively snapshots forwarded response bodies`() {
        val originalBody = "ok".toByteArray(Charsets.UTF_8)
        val response = ForwardedHttpResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf("content-length" to listOf("2")),
            body = originalBody,
        )

        originalBody[0] = 'n'.code.toByte()
        response.body[1] = 'o'.code.toByte()

        assertEquals("ok", response.body.toString(Charsets.UTF_8))
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 2\r\n" +
                "\r\n" +
                "ok",
            response.toHttpString(),
        )
    }

    @Test
    fun `byte rendering preserves non-utf8 response bodies and string rendering rejects them`() {
        val binaryBody = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val response = ForwardedHttpResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = linkedMapOf("content-length" to listOf(binaryBody.size.toString())),
            body = binaryBody,
        )

        assertEquals(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "content-length: 4\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8).toList() + binaryBody.toList(),
            response.toByteArray().toList(),
        )
        assertFailsWith<IllegalStateException> {
            response.toHttpString()
        }
    }

    @Test
    fun `rejects unsafe public construction inputs that could split forwarded responses`() {
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK\r\nInjected: secret",
                headers = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("x-test\r\ninjected" to listOf("value")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForwardedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("x-test" to listOf("safe\r\nInjected: secret")),
            )
        }
    }
}
