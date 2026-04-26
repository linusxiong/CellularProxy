package com.cellularproxy.proxy.errors

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionRejectionReason
import com.cellularproxy.proxy.admission.ManagementAuthorizationRejectionReason
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionRejectionReason
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ProxyRequestLineRejectionReason
import com.cellularproxy.shared.proxy.ProxyAuthenticationRejectionReason
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProxyErrorResponseMapperTest {
    @Test
    fun `maps malformed request parsing to a generic bad request response`() {
        val decision = ProxyErrorResponseMapper.map(
            ProxyServerFailure.HeaderBlockParse(
                reason = HttpRequestHeaderBlockRejectionReason.RequestLineRejected,
                requestLineRejectionReason = ProxyRequestLineRejectionReason.InvalidAbsoluteUri,
            ),
        )

        val response = assertEmitted(decision)

        assertEquals(400, response.statusCode)
        assertEquals("Bad Request", response.reasonPhrase)
        assertEquals("close", response.headers["Connection"])
        assertEquals("Bad request\n", response.body)
    }

    @Test
    fun `maps proxy authentication failures to a proxy authentication challenge`() {
        val failures = listOf(
            ProxyServerFailure.Admission(
                ProxyRequestAdmissionRejectionReason.DuplicateProxyAuthorizationHeader,
            ),
            ProxyServerFailure.Admission(
                ProxyRequestAdmissionRejectionReason.ProxyAuthentication(
                    ProxyAuthenticationRejectionReason.MissingAuthorization,
                ),
            ),
        )

        failures.forEach { failure ->
            val response = assertEmitted(ProxyErrorResponseMapper.map(failure))

            assertEquals(407, response.statusCode)
            assertEquals("Proxy Authentication Required", response.reasonPhrase)
            assertEquals("Basic realm=\"CellularProxy\"", response.headers["Proxy-Authenticate"])
        }
    }

    @Test
    fun `maps management authorization failures to a bearer authentication challenge`() {
        ManagementAuthorizationRejectionReason.entries.forEach { rejectionReason ->
            val decision = ProxyErrorResponseMapper.map(
                ProxyServerFailure.Admission(
                    ProxyRequestAdmissionRejectionReason.ManagementAuthorization(rejectionReason),
                ),
            )

            val response = assertEmitted(decision)

            assertEquals(401, response.statusCode)
            assertEquals("Unauthorized", response.reasonPhrase)
            assertEquals("Bearer", response.headers["WWW-Authenticate"])
        }
    }

    @Test
    fun `maps capacity selected-route and paused-proxy failures to service unavailable responses`() {
        val capacity = assertEmitted(
            ProxyErrorResponseMapper.map(
                ProxyServerFailure.ConnectionLimit(
                    ConnectionLimitAdmissionRejectionReason.MaximumConcurrentConnectionsReached(
                        activeConnections = 10,
                        maxConcurrentConnections = 10,
                    ),
                ),
            ),
        )
        val route = assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.SelectedRouteUnavailable))
        val paused = assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.ProxyRequestsPaused))

        assertEquals(503, capacity.statusCode)
        assertEquals("Service Unavailable", capacity.reasonPhrase)
        assertEquals(503, route.statusCode)
        assertEquals("Service Unavailable", route.reasonPhrase)
        assertEquals(503, paused.statusCode)
        assertEquals("Service Unavailable", paused.reasonPhrase)
        assertEquals("Service unavailable\n", paused.body)
        assertEquals("close", paused.headers["Connection"])
    }

    @Test
    fun `maps outbound failures and timeouts to safe generic responses`() {
        assertEquals(502, assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.DnsResolutionFailed)).statusCode)
        assertEquals(502, assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.OutboundConnectionFailed)).statusCode)
        assertEquals(504, assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.OutboundConnectionTimeout)).statusCode)
        assertEquals(408, assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.IdleTimeout)).statusCode)
    }

    @Test
    fun `suppresses responses for client disconnect cleanup failures`() {
        assertIs<ProxyErrorResponseDecision.Suppress>(
            ProxyErrorResponseMapper.map(ProxyServerFailure.ClientDisconnected),
        )
    }

    @Test
    fun `renders byte-accurate HTTP responses without leaking caller supplied details`() {
        val response = assertEmitted(ProxyErrorResponseMapper.map(ProxyServerFailure.DnsResolutionFailed))
        val rendered = response.toHttpString()

        assertContains(rendered, "HTTP/1.1 502 Bad Gateway\r\n")
        assertContains(rendered, "Content-Type: text/plain; charset=utf-8\r\n")
        assertContains(rendered, "Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
        assertContains(rendered, "\r\n\r\n${response.body}")
        assertEquals(rendered.toByteArray(Charsets.UTF_8).toList(), response.toByteArray().toList())
        assertEquals(
            "HTTP/1.1 502 Bad Gateway\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: 12\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "Bad gateway\n",
            rendered,
        )
    }

    @Test
    fun `rejects response splitting in public response construction`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error\r\nX-Leak: secret",
                headers = emptyMap(),
                body = "failure",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error",
                headers = mapOf("X-Test\r\nInjected" to "value"),
                body = "failure",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error",
                headers = mapOf("X-Test" to "safe\r\nInjected: secret"),
                body = "failure",
            )
        }
    }

    @Test
    fun `defensively snapshots and exposes immutable response headers`() {
        val mutableHeaders = linkedMapOf("X-Test" to "one")
        val response = ProxyErrorResponse(
            statusCode = 500,
            reasonPhrase = "Server Error",
            headers = mutableHeaders,
            body = "failure",
        )

        mutableHeaders["X-Test"] = "two"
        assertEquals("one", response.headers["X-Test"])

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (response.headers as MutableMap<String, String>)["X-Test"] = "three"
        }
    }

    @Test
    fun `rejects ambiguous public response framing headers`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error",
                headers = mapOf("Content-Length" to "999"),
                body = "failure",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error",
                headers = mapOf(
                    "Content-Length" to "7",
                    "Transfer-Encoding" to "chunked",
                ),
                body = "failure",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyErrorResponse(
                statusCode = 500,
                reasonPhrase = "Server Error",
                headers = linkedMapOf(
                    "Content-Length" to "7",
                    "content-length" to "7",
                ),
                body = "failure",
            )
        }
    }

    private fun assertEmitted(decision: ProxyErrorResponseDecision): ProxyErrorResponse =
        assertIs<ProxyErrorResponseDecision.Emit>(decision).response
}
