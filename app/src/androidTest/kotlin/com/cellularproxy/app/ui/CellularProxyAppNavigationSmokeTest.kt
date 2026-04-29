package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Cloudflare
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Diagnostics
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.LogsAudit
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Rotation
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Settings
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.RootConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CellularProxyAppNavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CellularProxyComposeTestActivity>()

    @Test
    fun topLevelNavigationShowsEveryOperatorDestination() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                Scaffold(
                    bottomBar = {
                        CellularProxyNavigationBar(navController)
                    },
                ) { contentPadding ->
                    CellularProxyNavigationHost(
                        navController = navController,
                        onStartProxyService = {},
                        onStopProxyService = {},
                        onRestartProxyService = {},
                        settingsInitialConfigProvider = AppConfig::default,
                        settingsSaveConfig = {},
                        settingsLoadSensitiveConfig = ::sensitiveConfig,
                        settingsSaveSensitiveConfig = {},
                        logsAuditRowsProvider = { emptyList() },
                        logsAuditRedactionSecretsProvider = {
                            LogRedactionSecrets(
                                managementApiToken = sensitiveConfig().managementApiToken,
                                proxyCredential = sensitiveConfig().proxyCredential.canonicalBasicPayload(),
                            )
                        },
                        proxyStatusProvider = {
                            ProxyServiceStatus.stopped(
                                configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                            )
                        },
                        recentTrafficProvider = { null },
                        observedNetworksProvider = { emptyList() },
                        cloudflareManagementRoundTripProvider = { null },
                        latestCloudflareManagementApiCheck = DashboardCloudflareManagementApiCheck.NotRun,
                        localManagementApiProbeResultProvider = { LocalManagementApiProbeResult.Unavailable },
                        cloudflareManagementApiProbeResultProvider = { CloudflareManagementApiProbeResult.NotConfigured },
                        onRefreshProxyStatus = {},
                        dispatchLocalManagementApiAction = { _: LocalManagementApiAction -> },
                        rotationStatusProvider = { RotationStatus.idle() },
                        rotationCooldownRemainingSecondsProvider = { null },
                        currentPublicIpProvider = { null },
                        onCopyText = {},
                        onExportLogsAuditBundle = {},
                        onRecordLogsAuditAction = {},
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    )
                }
            }
        }

        assertTextExists("Stopped")

        clickPrimaryNavigationDestination(Settings)
        composeRule.onNodeWithText("Listen host").assertExists()
        clickPrimaryNavigationDestination(LogsAudit)
        composeRule.onNodeWithText("No log or audit records match the current filters.").assertExists()
        assertDestinationRenders(navController, Cloudflare, "Tunnel enabled")
        assertDestinationRenders(navController, Rotation, "Root availability")
        assertDestinationRenders(navController, Diagnostics, "Root availability")
    }

    @Test
    fun dashboardRendersDisabledRootAndDisabledCloudflareState() {
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDashboardScreen(
                    state =
                        DashboardScreenState.from(
                            DashboardStatusModel.from(
                                config = AppConfig.default(),
                                status = ProxyServiceStatus.stopped(),
                            ),
                        ),
                )
            }
        }

        assertTextExists("Root availability")
        assertTextExists("Cloudflare tunnel")
        assertTextExists("Disabled")
        assertTextExists("Remote management unavailable")
    }

    @Test
    fun dashboardRendersNoRootUnavailableState() {
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDashboardScreen(
                    state =
                        DashboardScreenState.from(
                            DashboardStatusModel.from(
                                config =
                                    AppConfig.default().copy(
                                        root = RootConfig(operationsEnabled = true),
                                    ),
                                status =
                                    ProxyServiceStatus.stopped(
                                        rootAvailability = RootAvailabilityStatus.Unavailable,
                                    ),
                            ),
                        ),
                )
            }
        }

        assertTextExists("Root availability")
        assertTextExists("Unavailable")
        assertTextExists("Root access is unavailable")
    }

    @Test
    fun dashboardRendersRootAvailableAndConnectedCloudflareState() {
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDashboardScreen(
                    state =
                        DashboardScreenState.from(
                            DashboardStatusModel.from(
                                config =
                                    AppConfig.default().copy(
                                        root = RootConfig(operationsEnabled = true),
                                        cloudflare =
                                            CloudflareConfig(
                                                enabled = true,
                                                tunnelTokenPresent = true,
                                            ),
                                    ),
                                status =
                                    ProxyServiceStatus.running(
                                        listenHost = "127.0.0.1",
                                        listenPort = 8181,
                                        configuredRoute = RouteTarget.Cellular,
                                        boundRoute =
                                            NetworkDescriptor(
                                                id = "cellular-network",
                                                category = NetworkCategory.Cellular,
                                                displayName = "Carrier LTE",
                                                isAvailable = true,
                                            ),
                                        publicIp = "203.0.113.10",
                                        hasHighSecurityRisk = false,
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
                            ),
                        ),
                    actionsEnabled = true,
                )
            }
        }

        assertTextExists("Root availability")
        assertTextExists("Available")
        assertTextExists("Cloudflare tunnel")
        assertTextExists("Connected")
        assertTextExists("Remote management available")
        assertTextExists("Carrier LTE (Cellular, available)")
    }

    @Test
    fun rotationCheckRootDispatchesRootStatusThroughNavigationHost() {
        val dispatchedActions = mutableListOf<LocalManagementApiAction>()
        val rootEnabledConfig =
            AppConfig.default().copy(
                root = RootConfig(operationsEnabled = true),
            )
        lateinit var navController: NavHostController

        composeRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                Scaffold(
                    bottomBar = {
                        CellularProxyNavigationBar(navController)
                    },
                ) { contentPadding ->
                    CellularProxyNavigationHost(
                        navController = navController,
                        onStartProxyService = {},
                        onStopProxyService = {},
                        onRestartProxyService = {},
                        settingsInitialConfigProvider = { rootEnabledConfig },
                        settingsSaveConfig = {},
                        settingsLoadSensitiveConfig = ::sensitiveConfig,
                        settingsSaveSensitiveConfig = {},
                        logsAuditRowsProvider = { emptyList() },
                        logsAuditRedactionSecretsProvider = {
                            LogRedactionSecrets(
                                managementApiToken = sensitiveConfig().managementApiToken,
                                proxyCredential = sensitiveConfig().proxyCredential.canonicalBasicPayload(),
                            )
                        },
                        proxyStatusProvider = {
                            ProxyServiceStatus.stopped(
                                configuredRoute = rootEnabledConfig.network.defaultRoutePolicy,
                                rootAvailability = RootAvailabilityStatus.Available,
                            )
                        },
                        recentTrafficProvider = { null },
                        observedNetworksProvider = { emptyList() },
                        cloudflareManagementRoundTripProvider = { null },
                        latestCloudflareManagementApiCheck = DashboardCloudflareManagementApiCheck.NotRun,
                        localManagementApiProbeResultProvider = { LocalManagementApiProbeResult.Unavailable },
                        cloudflareManagementApiProbeResultProvider = { CloudflareManagementApiProbeResult.NotConfigured },
                        onRefreshProxyStatus = {},
                        dispatchLocalManagementApiAction = dispatchedActions::add,
                        rotationStatusProvider = { RotationStatus.idle() },
                        rotationCooldownRemainingSecondsProvider = { null },
                        currentPublicIpProvider = { null },
                        onCopyText = {},
                        onExportLogsAuditBundle = {},
                        onRecordLogsAuditAction = {},
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    )
                }
            }
        }

        composeRule.runOnUiThread {
            navController.navigateToCellularProxyDestination(Rotation)
        }
        composeRule
            .onNodeWithText("Check root")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            dispatchedActions.isNotEmpty()
        }
        assertEquals(listOf(LocalManagementApiAction.RootStatus), dispatchedActions)
    }

    private fun assertDestinationRenders(
        navController: NavHostController,
        destination: CellularProxyNavigationDestination,
        expectedScreenText: String,
    ) {
        composeRule.runOnUiThread {
            navController.navigateToCellularProxyDestination(destination)
        }
        composeRule.onNodeWithText(expectedScreenText).assertExists()
    }

    private fun clickPrimaryNavigationDestination(destination: CellularProxyNavigationDestination) {
        composeRule
            .onNode(
                hasTestTag("navigation-item-${destination.route}"),
            ).performClick()
    }

    private fun assertTextExists(text: String) {
        assertTrue(
            composeRule
                .onAllNodes(hasText(text))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
)
