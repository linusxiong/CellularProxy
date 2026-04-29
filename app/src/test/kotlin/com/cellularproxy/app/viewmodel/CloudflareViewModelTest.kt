package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.ui.CloudflareScreenAction
import com.cellularproxy.app.ui.CloudflareScreenEffect
import com.cellularproxy.app.ui.CloudflareScreenEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareViewModelTest {
    @Test
    fun `view model exposes cloudflare state as state flow and updates after events`() {
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val actions = mutableListOf<CloudflareScreenAction>()
        val viewModel =
            CloudflareViewModel(
                configProvider = { enabledCloudflareConfigForViewModelTest(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action ->
                    actions += action
                    tunnelStatus = CloudflareTunnelStatus.starting()
                },
            )

        assertEquals("Stopped", viewModel.state.value.lifecycleState)

        viewModel.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)
        assertEquals("Starting", viewModel.state.value.lifecycleState)
        assertFalse(CloudflareScreenAction.StartTunnel in viewModel.state.value.availableActions)
    }

    @Test
    fun `view model exposes one-shot controller effects without retaining them`() {
        val viewModel =
            CloudflareViewModel(
                configProvider = { enabledCloudflareConfigForViewModelTest(tokenPresent = true) },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
            )

        viewModel.handle(CloudflareScreenEvent.CopyDiagnostics)

        assertTrue(viewModel.consumeEffects().single() is CloudflareScreenEffect.CopyText)
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}

private fun enabledCloudflareConfigForViewModelTest(tokenPresent: Boolean): AppConfig {
    val defaultConfig = AppConfig.default()
    return defaultConfig.copy(
        cloudflare =
            defaultConfig.cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = tokenPresent,
            ),
    )
}
