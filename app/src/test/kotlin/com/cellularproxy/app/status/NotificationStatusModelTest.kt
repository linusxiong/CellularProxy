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
import kotlin.test.assertTrue

class NotificationStatusModelTest {
    @Test
    fun `running notification shows route endpoint metrics and stop action`() {
        val model =
            NotificationStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = true),
                    ),
                status =
                    ProxyServiceStatus.running(
                        listenHost = "0.0.0.0",
                        listenPort = 8080,
                        configuredRoute = RouteTarget.Cellular,
                        boundRoute =
                            NetworkDescriptor(
                                id = "cell-1",
                                category = NetworkCategory.Cellular,
                                displayName = "Carrier LTE",
                                isAvailable = true,
                            ),
                        publicIp = "203.0.113.42",
                        hasHighSecurityRisk = false,
                        cloudflare = CloudflareTunnelStatus.connected(),
                        rootAvailability = RootAvailabilityStatus.Unavailable,
                        metrics =
                            ProxyTrafficMetrics(
                                activeConnections = 3,
                                totalConnections = 9,
                                rejectedConnections = 2,
                                bytesReceived = 512,
                                bytesSent = 1_024,
                            ),
                    ),
            )

        assertEquals(NotificationServiceState.Running, model.serviceState)
        assertEquals("CellularProxy running", model.title)
        assertEquals("0.0.0.0:8080 | Carrier LTE | 3 active", model.contentText)
        assertEquals("IP 203.0.113.42 | Cloudflare connected | Root unavailable", model.detailText)
        assertEquals(NotificationPriority.Warning, model.priority)
        assertTrue(model.isOngoing)
        assertTrue(model.stopActionEnabled)
        assertEquals(setOf(NotificationWarning.RootUnavailable), model.warnings)
        assertEquals("Root access is unavailable", model.warningText)
    }

    @Test
    fun `notification shows root disabled when root operations are not opted in`() {
        val model =
            NotificationStatusModel.from(
                config =
                    AppConfig.default().copy(
                        root = RootConfig(operationsEnabled = false),
                    ),
                status =
                    ProxyServiceStatus.stopped(
                        rootAvailability = RootAvailabilityStatus.Available,
                    ),
            )

        assertEquals("IP unknown | Cloudflare disabled | Root disabled", model.detailText)
    }

    @Test
    fun `stopped notification is not ongoing and hides stop action`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status = ProxyServiceStatus.stopped(),
            )

        assertEquals(NotificationServiceState.Stopped, model.serviceState)
        assertEquals("CellularProxy stopped", model.title)
        assertFalse(model.isOngoing)
        assertFalse(model.stopActionEnabled)
        assertEquals(NotificationPriority.Status, model.priority)
    }

    @Test
    fun `notification warns for broad unauthenticated proxy before service starts`() {
        val model =
            NotificationStatusModel.from(
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

        assertEquals(setOf(NotificationWarning.BroadUnauthenticatedProxy), model.warnings)
        assertEquals("Proxy authentication is off on a broad listener", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `failed notification shows startup failure without raw Cloudflare diagnostics`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.PortAlreadyInUse,
                        cloudflare = CloudflareTunnelStatus.failed("Authorization: Bearer secret-token"),
                    ),
            )

        assertEquals(NotificationServiceState.Failed, model.serviceState)
        assertEquals("CellularProxy failed", model.title)
        assertEquals("0.0.0.0:8080 | Automatic | 0 active", model.contentText)
        assertEquals("IP unknown | Cloudflare failed | Root disabled", model.detailText)
        assertEquals(
            setOf(NotificationWarning.StartupFailed, NotificationWarning.CloudflareFailed),
            model.warnings,
        )
        assertEquals("Service startup failed | Cloudflare tunnel failed", model.warningText)
        assertFalse(model.warningText.orEmpty().contains("secret-token"))
        assertEquals(NotificationPriority.Warning, model.priority)
        assertFalse(model.isOngoing)
        assertFalse(model.stopActionEnabled)
    }
}
