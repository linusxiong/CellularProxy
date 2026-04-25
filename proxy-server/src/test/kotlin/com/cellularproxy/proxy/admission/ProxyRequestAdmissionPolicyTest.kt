package com.cellularproxy.proxy.admission

import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
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

class ProxyRequestAdmissionPolicyTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")
    private val config = ProxyRequestAdmissionConfig(
        proxyAuthentication = ProxyAuthenticationConfig(
            authEnabled = true,
            credential = credential,
        ),
        managementApiToken = "management-token",
    )

    @Test
    fun `accepts authenticated HTTP proxy requests`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "example.com",
                port = 80,
                originTarget = "/",
            ),
            headers = mapOf("proxy-authorization" to listOf(validProxyAuthorization())),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        val accepted = assertIs<ProxyRequestAdmissionDecision.Accepted>(decision)
        assertEquals(request.request, accepted.request)
        assertFalse(accepted.requiresAuditLog)
    }

    @Test
    fun `accepts authenticated CONNECT tunnel requests`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.ConnectTunnel(host = "example.com", port = 443),
            headers = mapOf("Proxy-Authorization" to listOf(validProxyAuthorization())),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        val accepted = assertIs<ProxyRequestAdmissionDecision.Accepted>(decision)
        assertEquals(request.request, accepted.request)
    }

    @Test
    fun `rejects proxy requests with duplicate proxy authorization headers`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = "GET",
                host = "example.com",
                port = 80,
                originTarget = "/",
            ),
            headers = mapOf(
                "Proxy-Authorization" to listOf(validProxyAuthorization()),
                "proxy-authorization" to listOf(validProxyAuthorization()),
            ),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.DuplicateProxyAuthorizationHeader,
            ),
            decision,
        )
    }

    @Test
    fun `rejects proxy requests using shared proxy authentication reasons`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.ConnectTunnel(host = "example.com", port = 443),
            headers = emptyMap(),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ProxyAuthentication(
                    ProxyAuthenticationRejectionReason.MissingAuthorization,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `accepts public management health without authorization`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.Management(
                method = HttpMethod.Get,
                originTarget = "/health",
                requiresToken = false,
                requiresAuditLog = false,
            ),
            headers = emptyMap(),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        val accepted = assertIs<ProxyRequestAdmissionDecision.Accepted>(decision)
        assertEquals(request.request, accepted.request)
        assertFalse(accepted.requiresAuditLog)
    }

    @Test
    fun `rejects public management health with duplicate authorization headers`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.Management(
                method = HttpMethod.Get,
                originTarget = "/health",
                requiresToken = false,
                requiresAuditLog = false,
            ),
            headers = mapOf(
                "Authorization" to listOf("Bearer one"),
                "authorization" to listOf("Bearer two"),
            ),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.DuplicateAuthorizationHeader,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `accepts management API requests with bearer token and preserves audit requirement`() {
        val request = ParsedHttpRequest(
            request = ParsedProxyRequest.Management(
                method = HttpMethod.Post,
                originTarget = "/api/service/stop",
                requiresToken = true,
                requiresAuditLog = true,
            ),
            headers = mapOf("authorization" to listOf("Bearer management-token")),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        val accepted = assertIs<ProxyRequestAdmissionDecision.Accepted>(decision)
        assertEquals(request.request, accepted.request)
        assertTrue(accepted.requiresAuditLog)
    }

    @Test
    fun `rejects management API requests with duplicate authorization headers`() {
        val request = authenticatedManagementRequest(
            headers = mapOf(
                "Authorization" to listOf("Bearer management-token"),
                "authorization" to listOf("Bearer management-token"),
            ),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.DuplicateAuthorizationHeader,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects management API requests without authorization`() {
        val request = authenticatedManagementRequest(headers = emptyMap())

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.MissingAuthorization,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects management API requests with unsupported authorization scheme`() {
        val request = authenticatedManagementRequest(
            headers = mapOf("authorization" to listOf("Basic abc123")),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.UnsupportedScheme,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects management API requests with blank bearer token`() {
        val request = authenticatedManagementRequest(
            headers = mapOf("authorization" to listOf("Bearer   ")),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.MalformedBearerToken,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects management API requests with wrong bearer token`() {
        val request = authenticatedManagementRequest(
            headers = mapOf("authorization" to listOf("Bearer wrong-token")),
        )

        val decision = ProxyRequestAdmissionPolicy.evaluate(config, request)

        assertEquals(
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ManagementAuthorization(
                    ManagementAuthorizationRejectionReason.TokenMismatch,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `compares management tokens through constant-time UTF-8 helper`() {
        assertTrue(ConstantTimeSecret.equalsUtf8("management-token", "management-token"))
        assertFalse(ConstantTimeSecret.equalsUtf8("management-token", "management-tokem"))
        assertFalse(ConstantTimeSecret.equalsUtf8("management-token", "management-token-extra"))
    }

    @Test
    fun `redacts secret-bearing admission config diagnostics`() {
        val rendered = config.toString()

        assertFalse(rendered.contains("proxy-user"))
        assertFalse(rendered.contains("proxy-pass"))
        assertFalse(rendered.contains("management-token"))
        assertTrue(rendered.contains("[REDACTED]"))
    }

    private fun authenticatedManagementRequest(
        headers: Map<String, List<String>>,
    ): ParsedHttpRequest =
        ParsedHttpRequest(
            request = ParsedProxyRequest.Management(
                method = HttpMethod.Get,
                originTarget = "/api/status",
                requiresToken = true,
                requiresAuditLog = false,
            ),
            headers = headers,
        )

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }
}
