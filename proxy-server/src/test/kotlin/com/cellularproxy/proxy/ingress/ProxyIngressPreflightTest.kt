package com.cellularproxy.proxy.ingress

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionRejectionReason
import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.proxy.protocol.ProxyRequestLineRejectionReason
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationRejectionReason
import com.cellularproxy.shared.proxy.ProxyCredential
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyIngressPreflightTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")
    private val config = ProxyIngressPreflightConfig(
        connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 2),
        requestAdmission = ProxyRequestAdmissionConfig(
            proxyAuthentication = ProxyAuthenticationConfig(
                authEnabled = true,
                credential = credential,
            ),
            managementApiToken = "management-token",
        ),
    )

    @Test
    fun `accepts authenticated proxy requests and reserves capacity before dispatch`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 1,
            headerBlock = "GET http://example.com/resource HTTP/1.1\r\n" +
                "Accept: text/plain\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n",
        )

        val accepted = assertIs<ProxyIngressPreflightDecision.Accepted>(decision)
        val request = assertIs<ParsedProxyRequest.HttpProxy>(accepted.request)
        assertEquals(request, accepted.httpRequest.request)
        assertEquals(listOf("text/plain"), accepted.httpRequest.headers["accept"])
        assertEquals(listOf(validProxyAuthorization()), accepted.httpRequest.headers["proxy-authorization"])
        assertEquals("example.com", request.host)
        assertEquals("/resource", request.originTarget)
        assertEquals(2L, accepted.activeConnectionsAfterAdmission)
        assertFalse(accepted.requiresAuditLog)
        assertFalse(accepted.toString().contains(validProxyAuthorization()))
        assertFalse(accepted.toString().contains("proxy-authorization", ignoreCase = true))
    }

    @Test
    fun `rejects at capacity before parsing request data`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 2,
            headerBlock = "not a request",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
        assertEquals(503, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `maps malformed headers to safe bad request responses`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 0,
            headerBlock = "GET http://example.com/#fragment HTTP/1.1\r\n\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        val failure = assertIs<ProxyServerFailure.HeaderBlockParse>(rejected.failure)
        assertEquals(HttpRequestHeaderBlockRejectionReason.RequestLineRejected, failure.reason)
        assertEquals(ProxyRequestLineRejectionReason.InvalidAbsoluteUri, failure.requestLineRejectionReason)
        assertEquals(400, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `maps proxy authentication rejection to safe proxy auth challenge`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 0,
            headerBlock = "CONNECT example.com:443 HTTP/1.1\r\n\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        val failure = assertIs<ProxyServerFailure.Admission>(rejected.failure)
        assertEquals(
            ProxyAuthenticationRejectionReason.MissingAuthorization,
            assertIs<ProxyRequestAdmissionRejectionReason.ProxyAuthentication>(
                failure.reason,
            ).reason,
        )
        assertEquals(407, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `accepts authenticated high-impact management requests and preserves audit metadata`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 0,
            headerBlock = "POST /api/service/stop HTTP/1.1\r\n" +
                "Authorization: Bearer management-token\r\n" +
                "\r\n",
        )

        val accepted = assertIs<ProxyIngressPreflightDecision.Accepted>(decision)
        val request = assertIs<ParsedProxyRequest.Management>(accepted.request)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/service/stop", request.originTarget)
        assertEquals(1L, accepted.activeConnectionsAfterAdmission)
        assertTrue(accepted.requiresAuditLog)
    }

    @Test
    fun `rejects authenticated HTTP proxy requests while proxy requests are paused`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config.copy(proxyRequestsPaused = true),
            activeConnections = 0,
            headerBlock = "GET http://example.com/resource HTTP/1.1\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        assertEquals(ProxyServerFailure.ProxyRequestsPaused, rejected.failure)
        assertEquals(503, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `rejects authenticated CONNECT requests while proxy requests are paused`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config.copy(proxyRequestsPaused = true),
            activeConnections = 0,
            headerBlock = "CONNECT example.com:443 HTTP/1.1\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        assertEquals(ProxyServerFailure.ProxyRequestsPaused, rejected.failure)
        assertEquals(503, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `keeps proxy authentication rejection ahead of paused proxy rejection`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config.copy(proxyRequestsPaused = true),
            activeConnections = 0,
            headerBlock = "CONNECT example.com:443 HTTP/1.1\r\n\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        val failure = assertIs<ProxyServerFailure.Admission>(rejected.failure)
        assertEquals(
            ProxyAuthenticationRejectionReason.MissingAuthorization,
            assertIs<ProxyRequestAdmissionRejectionReason.ProxyAuthentication>(
                failure.reason,
            ).reason,
        )
        assertEquals(407, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertFalse(rejected.requiresAuditLog)
    }

    @Test
    fun `allows management requests while proxy requests are paused`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config.copy(proxyRequestsPaused = true),
            activeConnections = 0,
            headerBlock = "GET /api/status HTTP/1.1\r\n" +
                "Authorization: Bearer management-token\r\n" +
                "\r\n",
        )

        val accepted = assertIs<ProxyIngressPreflightDecision.Accepted>(decision)
        val request = assertIs<ParsedProxyRequest.Management>(accepted.request)
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/status", request.originTarget)
        assertEquals(1L, accepted.activeConnectionsAfterAdmission)
        assertFalse(accepted.requiresAuditLog)
    }

    @Test
    fun `marks rejected management authorization attempts for audit logging`() {
        val decision = ProxyIngressPreflight.evaluate(
            config = config,
            activeConnections = 0,
            headerBlock = "GET /api/status HTTP/1.1\r\n\r\n",
        )

        val rejected = assertIs<ProxyIngressPreflightDecision.Rejected>(decision)
        assertIs<ProxyServerFailure.Admission>(rejected.failure)
        assertEquals(401, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertTrue(rejected.requiresAuditLog)
    }

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }
}
