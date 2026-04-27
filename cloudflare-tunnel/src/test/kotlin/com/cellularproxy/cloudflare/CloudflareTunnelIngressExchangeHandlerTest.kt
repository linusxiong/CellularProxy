package com.cellularproxy.cloudflare

import com.cellularproxy.shared.management.HttpMethod
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudflareTunnelIngressExchangeHandlerTest {
    @Test
    fun `forwards allowed origin-form requests to local management handler with original target`() {
        val handler =
            RecordingCloudflareLocalManagementHandler(
                CloudflareTunnelResponse.json(statusCode = 200, body = """{"ok":true}"""),
            )

        val health =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Get,
                target = "/health",
                localManagementHandler = handler,
            )
        val status =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Get,
                target = "/api/status?verbose=true",
                localManagementHandler = handler,
            )
        val command =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Post,
                target = "/api/cloudflare/start",
                localManagementHandler = handler,
            )

        assertIs<CloudflareTunnelIngressResult.Forwarded>(health)
        assertIs<CloudflareTunnelIngressResult.Forwarded>(status)
        assertIs<CloudflareTunnelIngressResult.Forwarded>(command)
        assertEquals(listOf("/health", "/api/status?verbose=true", "/api/cloudflare/start"), handler.originTargets)
        assertEquals(200, status.response.statusCode)
        assertEquals("""{"ok":true}""", status.response.body)
    }

    @Test
    fun `keeps legacy three-argument JVM handle overload for no-body requests`() {
        val overload =
            CloudflareTunnelIngressExchangeHandler::class.java.getMethod(
                "handle",
                HttpMethod::class.java,
                String::class.java,
                CloudflareLocalManagementHandler::class.java,
            )
        val handler =
            RecordingCloudflareLocalManagementHandler(
                CloudflareTunnelResponse.empty(statusCode = 204),
            )

        val result =
            overload.invoke(
                CloudflareTunnelIngressExchangeHandler,
                HttpMethod.Get,
                "/health",
                handler,
            )

        assertIs<CloudflareTunnelIngressResult.Forwarded>(result)
        val request = handler.requests.single()
        assertEquals(emptyMap(), request.headers)
        assertContentEquals(byteArrayOf(), request.body)
    }

    @Test
    fun `forwards allowed origin-form requests with duplicate-preserving headers and request body`() {
        val handler =
            RecordingCloudflareLocalManagementHandler(
                CloudflareTunnelResponse.empty(statusCode = 202),
            )
        val headers =
            linkedMapOf(
                "Authorization" to listOf("Bearer management-token"),
                "X-Cloudflare-Request" to listOf("first", "second"),
                "Content-Type" to listOf("application/json"),
            )
        val body = """{"enabled":true}""".toByteArray()

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Post,
                target = "/api/cloudflare/start",
                requestHeaders = headers,
                requestBody = body,
                localManagementHandler = handler,
            )

        assertIs<CloudflareTunnelIngressResult.Forwarded>(result)
        val request = handler.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/cloudflare/start", request.originTarget)
        assertEquals(headers, request.headers)
        assertContentEquals(body, request.body)
    }

    @Test
    fun `forwarded management request headers are defensive and preserve order`() {
        lateinit var capturedRequest: CloudflareLocalManagementRequest
        val duplicateValues = mutableListOf("first", "second")
        val headers =
            linkedMapOf<String, List<String>>(
                "Authorization" to mutableListOf("Bearer management-token"),
                "X-Cloudflare-Request" to duplicateValues,
                "Content-Type" to mutableListOf("application/json"),
            )

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Post,
                target = "/api/cloudflare/start",
                requestHeaders = headers,
                localManagementHandler =
                    CloudflareLocalManagementHandler { request ->
                        capturedRequest = request
                        CloudflareTunnelResponse.empty(statusCode = 202)
                    },
            )

        assertIs<CloudflareTunnelIngressResult.Forwarded>(result)
        headers["Added-Later"] = listOf("mutated")
        duplicateValues += "mutated"
        assertEquals(listOf("Authorization", "X-Cloudflare-Request", "Content-Type"), capturedRequest.headers.keys.toList())
        assertEquals(listOf("first", "second"), capturedRequest.headers["X-Cloudflare-Request"])

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capturedRequest.headers as MutableMap<String, List<String>>)["Other"] = listOf("value")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capturedRequest.headers.getValue("Authorization") as MutableList<String>) += "mutated"
        }
    }

    @Test
    fun `forwarded management request body is defensive against caller and handler mutation`() {
        lateinit var capturedRequest: CloudflareLocalManagementRequest
        val originalBody = byteArrayOf(1, 2, 3)

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Post,
                target = "/api/cloudflare/start",
                requestHeaders = emptyMap(),
                requestBody = originalBody,
                localManagementHandler =
                    CloudflareLocalManagementHandler { request ->
                        capturedRequest = request
                        CloudflareTunnelResponse.empty(statusCode = 202)
                    },
            )

        assertIs<CloudflareTunnelIngressResult.Forwarded>(result)
        originalBody[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), capturedRequest.body)

        capturedRequest.body[1] = 8
        assertContentEquals(byteArrayOf(1, 2, 3), capturedRequest.body)
    }

    @Test
    fun `rejects explicit proxy-form requests without invoking local management handler`() {
        val handler = RecordingCloudflareLocalManagementHandler(CloudflareTunnelResponse.empty(statusCode = 204))

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Get,
                target = "http://127.0.0.1:8080/api/status?token=secret",
                localManagementHandler = handler,
            )

        val rejected = assertIs<CloudflareTunnelIngressResult.Rejected>(result)
        assertEquals(403, rejected.response.statusCode)
        assertEquals("Forbidden", rejected.response.reasonPhrase)
        assertEquals("""{"error":"forbidden"}""", rejected.response.body)
        assertEquals(emptyList(), handler.originTargets)
    }

    @Test
    fun `rejects CONNECT authority requests without invoking local management handler`() {
        val handler = RecordingCloudflareLocalManagementHandler(CloudflareTunnelResponse.empty(statusCode = 204))

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Connect,
                target = "example.com:443",
                localManagementHandler = handler,
            )

        val rejected = assertIs<CloudflareTunnelIngressResult.Rejected>(result)
        assertEquals(403, rejected.response.statusCode)
        assertEquals(emptyList(), handler.originTargets)
    }

    @Test
    fun `rejects non-management origin-form requests without invoking local management handler`() {
        val handler = RecordingCloudflareLocalManagementHandler(CloudflareTunnelResponse.empty(statusCode = 204))

        val result =
            CloudflareTunnelIngressExchangeHandler.handle(
                method = HttpMethod.Get,
                target = "/proxy?token=secret",
                localManagementHandler = handler,
            )

        val rejected = assertIs<CloudflareTunnelIngressResult.Rejected>(result)
        assertEquals(403, rejected.response.statusCode)
        assertEquals(emptyList(), handler.originTargets)
    }

    @Test
    fun `rejects malformed origin-form targets without invoking local management handler`() {
        val handler = RecordingCloudflareLocalManagementHandler(CloudflareTunnelResponse.empty(statusCode = 204))
        val targets =
            listOf(
                "/api/status#fragment",
                "/api/status?verbose=true#fragment",
                "/api/status token",
                "/api/status\r\nHeader: injected",
                "api/status",
                "*",
            )

        targets.forEach { target ->
            val result =
                CloudflareTunnelIngressExchangeHandler.handle(
                    method = HttpMethod.Get,
                    target = target,
                    localManagementHandler = handler,
                )

            assertIs<CloudflareTunnelIngressResult.Rejected>(result)
        }

        assertEquals(emptyList(), handler.originTargets)
    }

    @Test
    fun `rejection responses and result diagnostics do not echo sensitive targets`() {
        val sensitiveTargets =
            listOf(
                "http://user:pass@example.com/api/status?token=secret",
                "example.com:443",
                "/proxy?token=secret",
            )

        sensitiveTargets.forEach { target ->
            val result =
                CloudflareTunnelIngressExchangeHandler.handle(
                    method = if (target == "example.com:443") HttpMethod.Connect else HttpMethod.Get,
                    target = target,
                    requestHeaders =
                        linkedMapOf(
                            "Authorization" to listOf("Bearer header-secret"),
                            "X-Diagnostic" to listOf("diagnostic-secret"),
                        ),
                    requestBody = "body-secret".toByteArray(),
                    localManagementHandler =
                        CloudflareLocalManagementHandler {
                            error("handler must not be invoked")
                        },
                )

            val rejected = assertIs<CloudflareTunnelIngressResult.Rejected>(result)
            val rendered = "${rejected.response} $rejected"

            assertFalse(rendered.contains(target), "Diagnostics must not include request target")
            assertFalse(rendered.contains("token=secret"), "Diagnostics must not include query tokens")
            assertFalse(rendered.contains("user:pass"), "Diagnostics must not include credentials")
            assertFalse(rendered.contains("header-secret"), "Diagnostics must not include header values")
            assertFalse(rendered.contains("diagnostic-secret"), "Diagnostics must not include header values")
            assertFalse(rendered.contains("body-secret"), "Diagnostics must not include request bodies")
            assertTrue(rendered.contains("Rejected"), "Diagnostics should still identify the result kind")
        }
    }

    @Test
    fun `response diagnostics do not echo reason phrases or body values`() {
        val response =
            CloudflareTunnelResponse(
                statusCode = 418,
                reasonPhrase = "token secret",
                headers = linkedMapOf("Content-Length" to "11"),
                body = "body secret",
            )

        val rendered = response.toString()

        assertTrue(rendered.contains("418"), "Diagnostics should keep status metadata")
        assertFalse(rendered.contains("token secret"), "Diagnostics must not include reason phrases")
        assertFalse(rendered.contains("body secret"), "Diagnostics must not include response bodies")
    }

    @Test
    fun `responses render safe HTTP wire bytes with CRLF framing`() {
        val response =
            CloudflareTunnelResponse.json(
                statusCode = 200,
                body = """{"message":"ok"}""",
            )

        val rendered = response.toHttpString()

        assertEquals(
            """
            HTTP/1.1 200 OK
            Content-Type: application/json; charset=utf-8
            Content-Length: 16
            Cache-Control: no-store

            {"message":"ok"}
            """.trimIndent().replace("\n", "\r\n"),
            rendered,
        )
        assertEquals(rendered, response.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `response bytes use UTF-8 content length and are defensive snapshots`() {
        val response =
            CloudflareTunnelResponse.json(
                statusCode = 200,
                body = "{\"message\":\"caf\u00e9\"}",
            )

        val first = response.toByteArray()
        first[first.lastIndex] = '!'.code.toByte()

        assertEquals("19", response.headers["Content-Length"])
        assertEquals(
            '}',
            response
                .toByteArray()
                .last()
                .toInt()
                .toChar(),
        )
    }

    @Test
    fun `public response construction rejects unsafe HTTP metadata`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK\r\nInjected: true",
                headers = emptyMap(),
                body = "",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("Bad Header" to "value"),
                body = "",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("X-Test" to "safe\r\nInjected: true"),
                body = "",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("Transfer-Encoding" to "chunked"),
                body = "",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("Content-Length" to "999"),
                body = "short",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = emptyMap(),
                body = "unframed",
            )
        }
    }

    private class RecordingCloudflareLocalManagementHandler(
        private val response: CloudflareTunnelResponse,
    ) : CloudflareLocalManagementHandler {
        val requests = mutableListOf<CloudflareLocalManagementRequest>()
        val originTargets = mutableListOf<String>()

        override fun handle(request: CloudflareLocalManagementRequest): CloudflareTunnelResponse {
            requests += request
            originTargets += request.originTarget
            return response
        }
    }
}
