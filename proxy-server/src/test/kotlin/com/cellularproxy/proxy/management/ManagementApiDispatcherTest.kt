package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagementApiDispatcherTest {
    @Test
    fun `dispatches routed management request to matching handler operation`() {
        val handler = RecordingManagementApiHandler(
            ManagementApiResponse.json(statusCode = 200, body = """{"state":"running"}"""),
        )

        val decision = ManagementApiDispatcher.dispatch(
            request = managementRequest(HttpMethod.Get, "/api/status"),
            handler = handler,
        )

        val responded = assertIs<ManagementApiDispatchDecision.Respond>(decision)
        assertEquals(200, responded.response.statusCode)
        assertEquals("""{"state":"running"}""", responded.response.body)
        assertEquals(listOf(ManagementApiOperation.Status), handler.operations)
        assertEquals(false, responded.requiresAuditLog)
    }

    @Test
    fun `preserves high-impact audit metadata when dispatching command endpoints`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 202))

        val decision = ManagementApiDispatcher.dispatch(
            request = managementRequest(HttpMethod.Post, "/api/rotate/mobile-data"),
            handler = handler,
        )

        val responded = assertIs<ManagementApiDispatchDecision.Respond>(decision)
        assertEquals(202, responded.response.statusCode)
        assertEquals(listOf(ManagementApiOperation.RotateMobileData), handler.operations)
        assertTrue(responded.requiresAuditLog)
    }

    @Test
    fun `wraps high-impact handler exceptions with operation and audit metadata`() {
        val failure = IOException("backend unavailable")

        val thrown = assertFailsWith<ManagementApiHandlerException> {
            ManagementApiDispatcher.dispatch(
                request = managementRequest(HttpMethod.Post, "/api/rotate/mobile-data"),
                handler = ManagementApiHandler { throw failure },
            )
        }

        assertEquals(ManagementApiOperation.RotateMobileData, thrown.operation)
        assertTrue(thrown.requiresAuditLog)
        assertSame(failure, thrown.cause)
    }

    @Test
    fun `wraps read-only handler exceptions with operation and no audit metadata`() {
        val failure = IOException("backend unavailable")

        val thrown = assertFailsWith<ManagementApiHandlerException> {
            ManagementApiDispatcher.dispatch(
                request = managementRequest(HttpMethod.Get, "/api/status"),
                handler = ManagementApiHandler { throw failure },
            )
        }

        assertEquals(ManagementApiOperation.Status, thrown.operation)
        assertEquals(false, thrown.requiresAuditLog)
        assertSame(failure, thrown.cause)
    }

    @Test
    fun `rethrows fatal handler errors unchanged`() {
        val failure = FatalHandlerError("vm failure")

        val thrown = assertFailsWith<FatalHandlerError> {
            ManagementApiDispatcher.dispatch(
                request = managementRequest(HttpMethod.Post, "/api/service/stop"),
                handler = ManagementApiHandler { throw failure },
            )
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `maps router rejections to safe management responses without invoking handler`() {
        val cases = mapOf(
            managementRequest(HttpMethod.Get, "/api/unknown") to 404,
            managementRequest(HttpMethod.Post, "/api/status") to 405,
            managementRequest(HttpMethod.Get, "/api/status?verbose=true") to 400,
        )

        cases.forEach { (request, expectedStatus) ->
            val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))

            val rejected = assertIs<ManagementApiDispatchDecision.Reject>(
                ManagementApiDispatcher.dispatch(request = request, handler = handler),
            )

            assertEquals(expectedStatus, rejected.response.statusCode)
            assertEquals(emptyList(), handler.operations)
            assertEquals(false, rejected.requiresAuditLog)
        }
    }

    @Test
    fun `preserves precomputed audit metadata when rejecting high-impact management request`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))

        val rejected = assertIs<ManagementApiDispatchDecision.Reject>(
            ManagementApiDispatcher.dispatch(
                request = managementRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/service/stop?reason=remote",
                    requiresAuditLog = true,
                ),
                handler = handler,
            ),
        )

        assertEquals(400, rejected.response.statusCode)
        assertEquals(emptyList(), handler.operations)
        assertTrue(rejected.requiresAuditLog)
    }

    @Test
    fun `method-not-allowed response includes allowed management method`() {
        val getOnlyEndpoint = assertIs<ManagementApiDispatchDecision.Reject>(
            ManagementApiDispatcher.dispatch(
                request = managementRequest(HttpMethod.Post, "/api/status"),
                handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204)),
            ),
        )
        val postOnlyEndpoint = assertIs<ManagementApiDispatchDecision.Reject>(
            ManagementApiDispatcher.dispatch(
                request = managementRequest(HttpMethod.Get, "/api/service/stop"),
                handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204)),
            ),
        )

        assertEquals(405, getOnlyEndpoint.response.statusCode)
        assertEquals("GET", getOnlyEndpoint.response.headers["Allow"])
        assertEquals(405, postOnlyEndpoint.response.statusCode)
        assertEquals("POST", postOnlyEndpoint.response.headers["Allow"])
    }

    @Test
    fun `management response renders byte accurate http response`() {
        val response = ManagementApiResponse.json(statusCode = 200, body = """{"message":"ok"}""")

        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: 16\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                """{"message":"ok"}""",
            response.toHttpString(),
        )
        assertEquals(response.toHttpString().toByteArray(Charsets.UTF_8).toList(), response.toByteArray().toList())
    }

    @Test
    fun `management response construction rejects unsafe framing and header values`() {
        assertFailsWith<IllegalArgumentException> {
            ManagementApiResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("Transfer-Encoding" to "chunked"),
                body = "ok",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ManagementApiResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("X-Unsafe" to "bad\r\nInjected: yes"),
                body = "ok",
            )
        }
    }

    @Test
    fun `management response factories keep protected default headers`() {
        val json = ManagementApiResponse.json(
            statusCode = 200,
            body = "{}",
            extraHeaders = linkedMapOf(
                "Content-Type" to "text/html",
                "Connection" to "keep-alive",
                "Cache-Control" to "public",
            ),
        )
        val empty = ManagementApiResponse.empty(
            statusCode = 204,
            extraHeaders = linkedMapOf(
                "Connection" to "keep-alive",
                "Cache-Control" to "public",
            ),
        )

        assertEquals("application/json; charset=utf-8", json.headers["Content-Type"])
        assertEquals("close", json.headers["Connection"])
        assertEquals("no-store", json.headers["Cache-Control"])
        assertEquals("close", empty.headers["Connection"])
        assertEquals("no-store", empty.headers["Cache-Control"])
    }

    private class RecordingManagementApiHandler(
        private val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            return response
        }
    }

    private class FatalHandlerError(message: String) : Error(message)

    private fun managementRequest(
        method: HttpMethod,
        originTarget: String,
        requiresAuditLog: Boolean = false,
    ): ParsedProxyRequest.Management =
        ParsedProxyRequest.Management(
            method = method,
            originTarget = originTarget,
            requiresToken = originTarget.startsWith("/api/"),
            requiresAuditLog = requiresAuditLog,
        )
}
