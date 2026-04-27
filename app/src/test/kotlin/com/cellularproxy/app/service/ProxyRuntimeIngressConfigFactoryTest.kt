package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionRejectionReason
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressPreflight
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightDecision
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationRejectionReason
import com.cellularproxy.shared.proxy.ProxyCredential
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProxyRuntimeIngressConfigFactoryTest {
    @Test
    fun `composed ingress config enforces persisted proxy credentials`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig = AppConfig.default(),
                sensitiveConfig = sensitiveConfig,
                maxConcurrentConnections = 2,
            )

        val accepted =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 0,
                headerBlock = proxyRequest(proxyAuthorization = basicProxyAuthorization("proxy-user", "proxy-password")),
            )
        val rejected =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 0,
                headerBlock = proxyRequest(proxyAuthorization = null),
            )

        assertIs<ProxyIngressPreflightDecision.Accepted>(accepted)
        assertEquals(1, accepted.activeConnectionsAfterAdmission)

        val rejection = assertIs<ProxyIngressPreflightDecision.Rejected>(rejected)
        val admission = assertIs<ProxyServerFailure.Admission>(rejection.failure)
        val proxyAuth = assertIs<ProxyRequestAdmissionRejectionReason.ProxyAuthentication>(admission.reason)
        assertEquals(ProxyAuthenticationRejectionReason.MissingAuthorization, proxyAuth.reason)
    }

    @Test
    fun `disabled proxy authentication is preserved in composed ingress config`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig =
                    AppConfig.default().copy(
                        proxy = AppConfig.default().proxy.copy(authEnabled = false),
                    ),
                sensitiveConfig = sensitiveConfig,
            )

        val accepted =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 0,
                headerBlock = proxyRequest(proxyAuthorization = null),
            )

        assertIs<ProxyIngressPreflightDecision.Accepted>(accepted)
    }

    @Test
    fun `composed ingress config enforces persisted management api token`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig = AppConfig.default(),
                sensitiveConfig = sensitiveConfig,
            )

        val accepted =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 0,
                headerBlock = managementRequest(authorization = "Bearer management-secret"),
            )
        val rejected =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 0,
                headerBlock = managementRequest(authorization = "Bearer wrong-token"),
            )

        assertIs<ProxyIngressPreflightDecision.Accepted>(accepted)
        val rejection = assertIs<ProxyIngressPreflightDecision.Rejected>(rejected)
        assertIs<ProxyServerFailure.Admission>(rejection.failure)
    }

    @Test
    fun `composed ingress config applies maximum concurrent connection limit`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig = AppConfig.default(),
                sensitiveConfig = sensitiveConfig,
                maxConcurrentConnections = 1,
            )

        val rejected =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 1,
                headerBlock = proxyRequest(proxyAuthorization = basicProxyAuthorization("proxy-user", "proxy-password")),
            )

        assertIs<ProxyIngressPreflightDecision.Rejected>(rejected)
        assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
    }

    @Test
    fun `composed ingress config uses persisted maximum concurrent connection limit by default`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig =
                    AppConfig.default().copy(
                        proxy = AppConfig.default().proxy.copy(maxConcurrentConnections = 1),
                    ),
                sensitiveConfig = sensitiveConfig,
            )

        val rejected =
            ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = 1,
                headerBlock = proxyRequest(proxyAuthorization = basicProxyAuthorization("proxy-user", "proxy-password")),
            )

        assertIs<ProxyIngressPreflightDecision.Rejected>(rejected)
        assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
    }

    @Test
    fun `composed ingress config rejects invalid connection limits`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig = AppConfig.default(),
                sensitiveConfig = sensitiveConfig,
                maxConcurrentConnections = 0,
            )
        }
    }

    @Test
    fun `composed ingress config diagnostics redact sensitive values`() {
        val config =
            ProxyRuntimeIngressConfigFactory.from(
                plainConfig = AppConfig.default(),
                sensitiveConfig = sensitiveConfig,
            )

        val diagnostic = config.toString()

        assertContains(diagnostic, "[REDACTED]")
        assertFalse(diagnostic.contains("proxy-user"))
        assertFalse(diagnostic.contains("proxy-password"))
        assertFalse(diagnostic.contains("management-secret"))
    }

    private val sensitiveConfig =
        SensitiveConfig(
            proxyCredential =
                ProxyCredential(
                    username = "proxy-user",
                    password = "proxy-password",
                ),
            managementApiToken = "management-secret",
        )

    private fun proxyRequest(proxyAuthorization: String?): String =
        buildString {
            append("GET http://example.com/resource HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            proxyAuthorization?.let { append("Proxy-Authorization: $it\r\n") }
            append("\r\n")
        }

    private fun managementRequest(authorization: String): String =
        "GET /api/status HTTP/1.1\r\n" +
            "Host: local.cellularproxy\r\n" +
            "Authorization: $authorization\r\n" +
            "\r\n"

    private fun basicProxyAuthorization(
        username: String,
        password: String,
    ): String {
        val encoded =
            Base64
                .getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }
}
