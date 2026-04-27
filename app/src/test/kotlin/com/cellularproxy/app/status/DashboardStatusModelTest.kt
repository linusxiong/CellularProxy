package com.cellularproxy.app.status

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RootConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardStatusModelTest {
    @Test
    fun `stopped service model shows configured endpoint and unavailable runtime fields`() {
        val model =
            DashboardStatusModel.from(
                config = AppConfig.default(),
                status = ProxyServiceStatus.stopped(),
            )

        assertEquals(DashboardServiceState.Stopped, model.serviceState)
        assertEquals("0.0.0.0:8080", model.listenEndpoint)
        assertEquals(DashboardRouteTarget.Automatic, model.configuredRoute)
        assertNull(model.boundRoute)
        assertNull(model.publicIp)
        assertEquals(0, model.activeConnections)
        assertEquals(0, model.totalConnections)
        assertEquals(0, model.rejectedConnections)
        assertEquals(DashboardCloudflareState.Disabled, model.cloudflare.state)
        assertFalse(model.cloudflare.remoteManagementAvailable)
        assertEquals(DashboardRootState.Disabled, model.root)
        assertEquals(emptySet(), model.warnings)
    }

    @Test
    fun `running service model prefers runtime endpoint route public ip and metrics`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.running(
                        listenHost = "127.0.0.1",
                        listenPort = 8181,
                        configuredRoute = RouteTarget.Cellular,
                        boundRoute =
                            NetworkDescriptor(
                                id = "cell-1",
                                category = NetworkCategory.Cellular,
                                displayName = "Carrier LTE",
                                isAvailable = true,
                            ),
                        publicIp = "203.0.113.10",
                        hasHighSecurityRisk = true,
                        cloudflare = CloudflareTunnelStatus.connected(),
                        rootAvailability = RootAvailabilityStatus.Available,
                        metrics =
                            ProxyTrafficMetrics(
                                activeConnections = 2,
                                totalConnections = 7,
                                rejectedConnections = 1,
                                bytesReceived = 1_024,
                                bytesSent = 2_048,
                            ),
                    ),
            )

        assertEquals(DashboardServiceState.Running, model.serviceState)
        assertEquals("127.0.0.1:8181", model.listenEndpoint)
        assertEquals(DashboardRouteTarget.Cellular, model.configuredRoute)
        assertEquals(
            DashboardBoundRoute(
                category = DashboardNetworkCategory.Cellular,
                displayName = "Carrier LTE",
                isAvailable = true,
            ),
            model.boundRoute,
        )
        assertEquals("203.0.113.10", model.publicIp)
        assertEquals(2, model.activeConnections)
        assertEquals(7, model.totalConnections)
        assertEquals(1, model.rejectedConnections)
        assertEquals(1_024, model.bytesReceived)
        assertEquals(2_048, model.bytesSent)
        assertEquals(DashboardCloudflareState.Connected, model.cloudflare.state)
        assertTrue(model.cloudflare.remoteManagementAvailable)
        assertEquals(DashboardRootState.Available, model.root)
        assertEquals(setOf(DashboardWarning.BroadUnauthenticatedProxy), model.warnings)
    }

    @Test
    fun `model warns about broad unauthenticated proxy even before service starts`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        proxy =
                            ProxyConfig(
                                listenHost = "0.0.0.0",
                                listenPort = 8080,
                                authEnabled = false,
                            ),
                    ),
                status = ProxyServiceStatus.stopped(),
            )

        assertEquals(setOf(DashboardWarning.BroadUnauthenticatedProxy), model.warnings)
    }

    @Test
    fun `model carries startup and Cloudflare failure warnings with safe failure summary`() {
        val model =
            DashboardStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.PortAlreadyInUse,
                        cloudflare = CloudflareTunnelStatus.failed("invalid tunnel token abc123"),
                    ),
            )

        assertEquals(DashboardServiceState.Failed, model.serviceState)
        assertEquals(ProxyStartupError.PortAlreadyInUse, model.startupError)
        assertEquals(DashboardCloudflareState.Failed, model.cloudflare.state)
        assertEquals("Cloudflare tunnel failed", model.cloudflare.failureReason)
        assertEquals(
            setOf(DashboardWarning.StartupFailed, DashboardWarning.CloudflareFailed),
            model.warnings,
        )
    }

    @Test
    fun `model exposes explicit no-root state for dashboard display`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Unavailable,
                    ),
            )

        assertEquals(DashboardRootState.Unavailable, model.root)
    }

    @Test
    fun `model warns when root operations are enabled but root is unavailable`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Unavailable,
                    ),
            )

        assertEquals(setOf(DashboardWarning.RootUnavailable), model.warnings)
    }

    @Test
    fun `model shows root operations disabled before reporting runtime root availability`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = false),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Available,
                    ),
            )

        assertEquals(DashboardRootState.Disabled, model.root)
    }

    @Test
    fun `model shows root availability when root operations are enabled`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Available,
                    ),
            )

        assertEquals(DashboardRootState.Available, model.root)
    }

    @Test
    fun `model shows unknown root availability when root operations are enabled and runtime is unknown`() {
        val model =
            DashboardStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Unknown,
                    ),
            )

        assertEquals(DashboardRootState.Unknown, model.root)
    }
}
