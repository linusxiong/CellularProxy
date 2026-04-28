package com.cellularproxy.app.ui

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
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
