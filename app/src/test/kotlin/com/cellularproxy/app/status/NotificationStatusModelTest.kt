package com.cellularproxy.app.status

import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
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
            setOf(
                NotificationWarning.StartupFailed,
                NotificationWarning.CloudflareFailed,
                NotificationWarning.PortAlreadyInUse,
            ),
            model.warnings,
        )
        assertEquals(
            "Service startup failed | Cloudflare tunnel failed | Proxy port is already in use",
            model.warningText,
        )
        assertFalse(model.warningText.orEmpty().contains("secret-token"))
        assertEquals(NotificationPriority.Warning, model.priority)
        assertFalse(model.isOngoing)
        assertFalse(model.stopActionEnabled)
    }

    @Test
    fun `notification warns when Cloudflare tunnel is degraded`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.stopped(
                        cloudflare = CloudflareTunnelStatus.degraded(),
                    ),
            )

        assertEquals(setOf(NotificationWarning.CloudflareDegraded), model.warnings)
        assertEquals("Cloudflare tunnel is degraded", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns when connected Cloudflare management api check is failing`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.stopped(
                        cloudflare = CloudflareTunnelStatus.connected(),
                    ),
                latestCloudflareManagementApiCheck = DashboardCloudflareManagementApiCheck.Failed,
            )

        assertEquals(setOf(NotificationWarning.CloudflareManagementApiCheckFailing), model.warnings)
        assertEquals("Cloudflare management API check is failing", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns specifically when proxy port is already in use at startup`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.PortAlreadyInUse,
                    ),
            )

        assertEquals(
            setOf(NotificationWarning.StartupFailed, NotificationWarning.PortAlreadyInUse),
            model.warnings,
        )
        assertEquals("Service startup failed | Proxy port is already in use", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns specifically when proxy configuration is invalid at startup`() {
        listOf(
            Triple(
                ProxyStartupError.InvalidListenAddress,
                NotificationWarning.InvalidListenAddress,
                "Service startup failed | Proxy listen address is invalid",
            ),
            Triple(
                ProxyStartupError.InvalidListenPort,
                NotificationWarning.InvalidListenPort,
                "Service startup failed | Proxy listen port is invalid",
            ),
            Triple(
                ProxyStartupError.InvalidMaxConcurrentConnections,
                NotificationWarning.InvalidMaxConcurrentConnections,
                "Service startup failed | Proxy connection limit is invalid",
            ),
        ).forEach { (startupError, warning, warningText) ->
            val model =
                NotificationStatusModel.from(
                    config = AppConfig.default(),
                    status = ProxyServiceStatus.failed(startupError = startupError),
                )

            assertEquals(
                setOf(NotificationWarning.StartupFailed, warning),
                model.warnings,
                "startupError=$startupError",
            )
            assertEquals(
                warningText,
                model.warningText,
                "startupError=$startupError",
            )
            assertEquals(NotificationPriority.Warning, model.priority)
        }
    }

    @Test
    fun `notification warns specifically when selected route is unavailable at startup`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.UnavailableSelectedRoute,
                    ),
            )

        assertEquals(
            setOf(NotificationWarning.StartupFailed, NotificationWarning.SelectedRouteUnavailable),
            model.warnings,
        )
        assertEquals("Service startup failed | Selected route is unavailable", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns when running bound route becomes unavailable`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
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
                                isAvailable = false,
                            ),
                        publicIp = null,
                        hasHighSecurityRisk = false,
                    ),
            )

        assertEquals(setOf(NotificationWarning.SelectedRouteUnavailable), model.warnings)
        assertEquals("Selected route is unavailable", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns specifically when Cloudflare is enabled but tunnel token is missing at startup`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.MissingCloudflareTunnelToken,
                    ),
            )

        assertEquals(
            setOf(NotificationWarning.StartupFailed, NotificationWarning.CloudflareTokenMissing),
            model.warnings,
        )
        assertEquals("Service startup failed | Cloudflare tunnel token is missing", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns when Cloudflare is enabled but tunnel token is missing before startup fails`() {
        val model =
            NotificationStatusModel.from(
                config =
                    AppConfig.default().copy(
                        cloudflare =
                            CloudflareConfig(
                                enabled = true,
                                tunnelTokenPresent = false,
                            ),
                    ),
                status = ProxyServiceStatus.stopped(),
            )

        assertEquals(setOf(NotificationWarning.CloudflareTokenMissing), model.warnings)
        assertEquals("Cloudflare tunnel token is missing", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns specifically when management api token is missing at startup`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status =
                    ProxyServiceStatus.failed(
                        startupError = ProxyStartupError.MissingManagementApiToken,
                    ),
            )

        assertEquals(
            setOf(NotificationWarning.StartupFailed, NotificationWarning.ManagementApiTokenMissing),
            model.warnings,
        )
        assertEquals("Service startup failed | Management API token is missing", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns when management api token is missing before startup fails`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status = ProxyServiceStatus.stopped(),
                managementApiTokenPresent = false,
            )

        assertEquals(setOf(NotificationWarning.ManagementApiTokenMissing), model.warnings)
        assertEquals("Management API token is missing", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }

    @Test
    fun `notification warns when sensitive configuration is invalid before startup fails`() {
        val model =
            NotificationStatusModel.from(
                config = AppConfig.default(),
                status = ProxyServiceStatus.stopped(),
                invalidSensitiveConfigReason = SensitiveConfigInvalidReason.InvalidProxyCredential,
            )

        assertEquals(setOf(NotificationWarning.SensitiveConfigurationInvalid), model.warnings)
        assertEquals("Sensitive configuration is invalid", model.warningText)
        assertEquals(NotificationPriority.Warning, model.priority)
    }
}
