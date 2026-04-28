package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareScreenControllerTest {
    @Test
    fun `controller dispatches available lifecycle actions and refreshes screen state`() {
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val actions = mutableListOf<CloudflareScreenAction>()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action ->
                    actions += action
                    tunnelStatus =
                        when (action) {
                            CloudflareScreenAction.StartTunnel -> CloudflareTunnelStatus.starting()
                            else -> tunnelStatus
                        }
                },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)
        assertEquals("Starting", controller.state.lifecycleState)
        assertTrue(CloudflareScreenAction.StopTunnel in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses unavailable lifecycle actions`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = false) },
                tunnelStatusProvider = { CloudflareTunnelStatus.stopped() },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertTrue(actions.isEmpty())
        assertEquals(listOf(CloudflareScreenAction.CopyDiagnostics), controller.state.availableActions)
    }

    @Test
    fun `controller suppresses duplicate operations until provider state changes`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)
        assertFalse(CloudflareScreenAction.StartTunnel in controller.state.availableActions)

        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)

        tunnelStatus = CloudflareTunnelStatus.starting()
        controller.handle(CloudflareScreenEvent.Refresh)
        tunnelStatus = CloudflareTunnelStatus.failed("edge connection failed")
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.StartTunnel,
                CloudflareScreenAction.StartTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `controller allows another management tunnel test after round trip result changes`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var managementRoundTrip: String? = null
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { managementRoundTrip },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(listOf(CloudflareScreenAction.TestManagementTunnel), actions)
        assertFalse(CloudflareScreenAction.TestManagementTunnel in controller.state.availableActions)

        managementRoundTrip = "HTTP 200 OK"
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.TestManagementTunnel,
                CloudflareScreenAction.TestManagementTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `controller allows another management tunnel test after equal round trip result refresh`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var managementRoundTrip = "HTTP 200"
        var managementRoundTripVersion = 1L
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { managementRoundTrip },
                managementApiRoundTripVersionProvider = { managementRoundTripVersion },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(listOf(CloudflareScreenAction.TestManagementTunnel), actions)
        assertFalse(CloudflareScreenAction.TestManagementTunnel in controller.state.availableActions)

        managementRoundTrip = "HTTP 200"
        managementRoundTripVersion = 2L
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.TestManagementTunnel,
                CloudflareScreenAction.TestManagementTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `controller exposes pending management tunnel test as visible state until resolved`() {
        var managementRoundTripVersion = 1L
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { "HTTP 200" },
                managementApiRoundTripVersionProvider = { managementRoundTripVersion },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals("In progress: Test management tunnel", controller.state.pendingOperation)

        managementRoundTripVersion = 2L
        controller.handle(CloudflareScreenEvent.Refresh)

        assertEquals("None", controller.state.pendingOperation)
    }

    @Test
    fun `controller emits redacted diagnostics copy effect once`() {
        val controller =
            CloudflareScreenController(
                configProvider = {
                    enabledCloudflareConfig(tokenPresent = true).copy(
                        cloudflare =
                            enabledCloudflareConfig(tokenPresent = true).cloudflare.copy(
                                managementHostnameLabel = "https://example.test/manage?token=tunnel-secret",
                            ),
                    )
                },
                tunnelStatusProvider = {
                    CloudflareTunnelStatus.failed(
                        "Authorization: Bearer tunnel-secret\nhttps://example.test/api/status?token=tunnel-secret",
                    )
                },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                managementApiRoundTripProvider = { "HTTP 503 for token=tunnel-secret" },
                secrets = LogRedactionSecrets(cloudflareTunnelToken = "tunnel-secret"),
            )

        controller.handle(CloudflareScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as CloudflareScreenEffect.CopyText).text
        assertTrue(copyText.contains("Tunnel lifecycle: Failed"))
        assertFalse(copyText.contains("tunnel-secret"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller refreshes redaction secrets before copying diagnostics`() {
        var secrets = LogRedactionSecrets()
        val controller =
            CloudflareScreenController(
                configProvider = {
                    enabledCloudflareConfig(tokenPresent = true).copy(
                        cloudflare =
                            enabledCloudflareConfig(tokenPresent = true).cloudflare.copy(
                                managementHostnameLabel = "https://example.test/manage?token=fresh-secret",
                            ),
                    )
                },
                tunnelStatusProvider = {
                    CloudflareTunnelStatus.failed("Authorization: Bearer fresh-secret")
                },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                managementApiRoundTripProvider = { "HTTP 503 for token=fresh-secret" },
                secretsProvider = { secrets },
            )
        secrets = LogRedactionSecrets(cloudflareTunnelToken = "fresh-secret")

        controller.handle(CloudflareScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as CloudflareScreenEffect.CopyText).text
        assertFalse(copyText.contains("fresh-secret"))
        assertTrue(copyText.contains("[REDACTED]"))
    }

    @Test
    fun `cloudflare token status distinguishes missing invalid and valid stored tokens`() {
        assertEquals(CloudflareTokenStatus.Missing, cloudflareTokenStatusFrom(null))
        assertEquals(CloudflareTokenStatus.Missing, cloudflareTokenStatusFrom(""))
        assertEquals(CloudflareTokenStatus.Invalid, cloudflareTokenStatusFrom("not-a-tunnel-token"))
        assertEquals(
            CloudflareTokenStatus.Present,
            cloudflareTokenStatusFrom(
                "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0=",
            ),
        )
    }

    @Test
    fun `cloudflare token status projects safely from sensitive config load results`() {
        val validTunnelToken =
            "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="

        assertEquals(
            CloudflareTokenStatus.Present,
            cloudflareTokenStatusFrom(
                SensitiveConfigLoadResult.Loaded(
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                        managementApiToken = "management-token",
                        cloudflareTunnelToken = validTunnelToken,
                    ),
                ),
            ),
        )
        assertEquals(
            CloudflareTokenStatus.Missing,
            cloudflareTokenStatusFrom(SensitiveConfigLoadResult.MissingRequiredSecrets),
        )
        assertEquals(
            CloudflareTokenStatus.Invalid,
            cloudflareTokenStatusFrom(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ),
        )
    }

    private fun enabledCloudflareConfig(tokenPresent: Boolean): AppConfig {
        val defaultConfig = AppConfig.default()
        return defaultConfig.copy(
            cloudflare =
                defaultConfig.cloudflare.copy(
                    enabled = true,
                    tunnelTokenPresent = tokenPresent,
                ),
        )
    }
}
