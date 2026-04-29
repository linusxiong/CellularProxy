package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.status.DashboardServiceState
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.ui.DashboardScreenAction
import com.cellularproxy.app.ui.DashboardScreenEffect
import com.cellularproxy.app.ui.DashboardScreenEvent
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardViewModelTest {
    @Test
    fun `view model exposes dashboard state as state flow and updates after events`() {
        var proxyStatus = ProxyServiceStatus.stopped()
        val actions = mutableListOf<DashboardScreenAction>()
        val viewModel =
            DashboardViewModel(
                statusProvider = {
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status = proxyStatus,
                    )
                },
                actionHandler = { action ->
                    actions += action
                    proxyStatus = ProxyServiceStatus(ProxyServiceState.Starting)
                },
            )

        assertEquals(DashboardServiceState.Stopped, viewModel.state.value.status.serviceState)

        viewModel.handle(DashboardScreenEvent.StartProxy)

        assertEquals(listOf(DashboardScreenAction.StartProxy), actions)
        assertEquals(DashboardServiceState.Starting, viewModel.state.value.status.serviceState)
        assertFalse(DashboardScreenAction.StartProxy in viewModel.state.value.availableActions)
    }

    @Test
    fun `view model exposes one-shot controller effects without retaining them`() {
        val viewModel =
            DashboardViewModel(
                statusProvider = {
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status =
                            ProxyServiceStatus.running(
                                listenHost = "127.0.0.1",
                                listenPort = 8080,
                                configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                                boundRoute = null,
                                publicIp = null,
                                hasHighSecurityRisk = false,
                            ),
                    )
                },
            )

        viewModel.handle(DashboardScreenEvent.CopyProxyEndpoint)

        assertEquals(
            listOf(DashboardScreenEffect.CopyText("127.0.0.1:8080")),
            viewModel.consumeEffects(),
        )
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}
