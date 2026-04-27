package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionDecision
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionPolicy
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConnectTunnelEstablishmentRendererTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")
    private val admissionConfig =
        ProxyRequestAdmissionConfig(
            proxyAuthentication =
                ProxyAuthenticationConfig(
                    authEnabled = true,
                    credential = credential,
                ),
            managementApiToken = "management-token",
        )

    @Test
    fun `renders admitted connect request as connection established response`() {
        val request =
            ParsedHttpRequest(
                request =
                    ParsedProxyRequest.ConnectTunnel(
                        host = "example.com",
                        port = 443,
                    ),
                headers =
                    linkedMapOf(
                        "proxy-authorization" to listOf(validProxyAuthorization()),
                        "host" to listOf("example.com:443"),
                    ),
            )
        val accepted =
            assertIs<ProxyRequestAdmissionDecision.Accepted>(
                ProxyRequestAdmissionPolicy.evaluate(admissionConfig, request),
            )

        val plan = ConnectTunnelEstablishmentRenderer.render(accepted)

        assertEquals("example.com", plan.host)
        assertEquals(443, plan.port)
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", plan.response.toHttpString())
        assertEquals(
            plan.response
                .toHttpString()
                .toByteArray(Charsets.UTF_8)
                .toList(),
            plan.response.toByteArray().toList(),
        )
    }

    @Test
    fun `preserves bracketed ipv6 connect targets`() {
        val accepted =
            admitted(
                ParsedHttpRequest(
                    request =
                        ParsedProxyRequest.ConnectTunnel(
                            host = "[2001:db8::1]",
                            port = 8443,
                        ),
                    headers = mapOf("proxy-authorization" to listOf(validProxyAuthorization())),
                ),
            )

        val plan = ConnectTunnelEstablishmentRenderer.render(accepted)

        assertEquals("[2001:db8::1]", plan.host)
        assertEquals(8443, plan.port)
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", plan.response.toHttpString())
    }

    @Test
    fun `rejects non-connect requests because they cannot establish a tunnel`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishmentRenderer.render(
                admitted(
                    ParsedHttpRequest(
                        request =
                            ParsedProxyRequest.HttpProxy(
                                method = "GET",
                                host = "example.com",
                                port = 80,
                                originTarget = "/",
                            ),
                        headers = mapOf("proxy-authorization" to listOf(validProxyAuthorization())),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishmentRenderer.render(
                admitted(
                    ParsedHttpRequest(
                        request =
                            ParsedProxyRequest.Management(
                                method = HttpMethod.Get,
                                originTarget = "/health",
                                requiresToken = false,
                                requiresAuditLog = false,
                            ),
                        headers = emptyMap(),
                    ),
                ),
            )
        }
    }

    @Test
    fun `accepted admission decisions are sealed to a non-public implementation`() {
        val acceptedType = ProxyRequestAdmissionDecision.Accepted::class.java
        val permittedImplementations = acceptedType.permittedSubclasses.toList()

        assertEquals(true, acceptedType.isSealed)
        assertEquals(1, permittedImplementations.size)
        assertEquals(
            "com.cellularproxy.proxy.admission.AcceptedProxyRequestAdmissionDecision",
            permittedImplementations.single().name,
        )
        assertEquals(false, Modifier.isPublic(permittedImplementations.single().modifiers))
    }

    @Test
    fun `rejects unsafe public response construction inputs`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishedResponse(
                reasonPhrase = "Connection Established\r\nInjected: secret",
                headers = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishedResponse(
                reasonPhrase = "Connection Established",
                headers = mapOf("x-test\r\ninjected" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishedResponse(
                reasonPhrase = "Connection Established",
                headers = mapOf("x-test" to "safe\r\nInjected: secret"),
            )
        }
    }

    @Test
    fun `defensively snapshots response headers and exposes them as immutable`() {
        val originalHeaders = linkedMapOf("X-Tunnel" to "ready")
        val response = ConnectTunnelEstablishedResponse(headers = originalHeaders)

        originalHeaders["X-Tunnel"] = "mutated"

        assertEquals("ready", response.headers["X-Tunnel"])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (response.headers as MutableMap<String, String>)["X-Injected"] = "nope"
        }
    }

    @Test
    fun `rejects body framing headers because successful connect switches protocols`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishedResponse(
                reasonPhrase = "Connection Established",
                headers = mapOf("Content-Length" to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelEstablishedResponse(
                reasonPhrase = "Connection Established",
                headers = mapOf("Transfer-Encoding" to "chunked"),
            )
        }
    }

    private fun admitted(request: ParsedHttpRequest): ProxyRequestAdmissionDecision.Accepted =
        assertIs<ProxyRequestAdmissionDecision.Accepted>(
            ProxyRequestAdmissionPolicy.evaluate(admissionConfig, request),
        )

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }
}
