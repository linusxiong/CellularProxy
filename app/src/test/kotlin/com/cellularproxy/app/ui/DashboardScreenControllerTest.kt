package com.cellularproxy.app.ui

import com.cellularproxy.app.status.DashboardServiceState
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardScreenControllerTest {
    @Test
    fun `controller dispatches available service actions and refreshes screen state`() {
        var proxyStatus = ProxyServiceStatus.stopped()
        val actions = mutableListOf<DashboardScreenAction>()
        val controller =
            DashboardScreenController(
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

        controller.handle(DashboardScreenEvent.StartProxy)

        assertEquals(listOf(DashboardScreenAction.StartProxy), actions)
        assertEquals(DashboardServiceState.Starting, controller.state.status.serviceState)
        assertFalse(DashboardScreenAction.StartProxy in controller.state.availableActions)
        assertTrue(DashboardScreenAction.StopProxy in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses unavailable service actions`() {
        val actions = mutableListOf<DashboardScreenAction>()
        val controller =
            DashboardScreenController(
                statusProvider = {
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status = ProxyServiceStatus.stopped(),
                    )
                },
                actionHandler = { action -> actions += action },
            )

        controller.handle(DashboardScreenEvent.StopProxy)

        assertTrue(actions.isEmpty())
        assertTrue(DashboardScreenAction.StartProxy in controller.state.availableActions)
        assertFalse(DashboardScreenAction.StopProxy in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses duplicate service lifecycle actions until provider state changes`() {
        val actions = mutableListOf<DashboardScreenAction>()
        var proxyStatus = ProxyServiceStatus.stopped()
        val controller =
            DashboardScreenController(
                statusProvider = {
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status = proxyStatus,
                    )
                },
                actionHandler = { action -> actions += action },
            )

        controller.handle(DashboardScreenEvent.StartProxy)
        controller.handle(DashboardScreenEvent.StartProxy)

        assertEquals(listOf(DashboardScreenAction.StartProxy), actions)
        assertFalse(DashboardScreenAction.StartProxy in controller.state.availableActions)

        controller.handle(DashboardScreenEvent.Refresh)
        controller.handle(DashboardScreenEvent.StartProxy)

        assertEquals(listOf(DashboardScreenAction.StartProxy), actions)

        proxyStatus = ProxyServiceStatus(ProxyServiceState.Starting)
        controller.handle(DashboardScreenEvent.Refresh)
        proxyStatus = ProxyServiceStatus.failed(ProxyStartupError.PortAlreadyInUse)
        controller.handle(DashboardScreenEvent.Refresh)
        controller.handle(DashboardScreenEvent.StartProxy)

        assertEquals(
            listOf(
                DashboardScreenAction.StartProxy,
                DashboardScreenAction.StartProxy,
            ),
            actions,
        )
    }

    @Test
    fun `controller emits copy endpoint effect once from state`() {
        val controller =
            DashboardScreenController(
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

        controller.handle(DashboardScreenEvent.CopyProxyEndpoint)

        assertEquals(
            DashboardScreenEffect.CopyText("127.0.0.1:8080"),
            controller.consumeEffects().single(),
        )
        assertTrue(controller.consumeEffects().isEmpty())
    }
}
