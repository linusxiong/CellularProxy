package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.rotation.RotationStatus
import org.junit.Rule
import org.junit.Test

class CellularProxyDashboardRouteSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CellularProxyComposeTestActivity>()

    @Test
    fun dashboardStartAndStopActionsDispatchForegroundServiceCallbacks() {
        val proxyStatusState = mutableStateOf(ProxyServiceStatus.stopped())
        var startRequests = 0
        var stopRequests = 0

        composeRule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                CellularProxyNavigationHost(
                    navController = navController,
                    onStartProxyService = {
                        startRequests += 1
                        proxyStatusState.value = ProxyServiceStatus(ProxyServiceState.Starting)
                    },
                    onStopProxyService = {
                        stopRequests += 1
                        proxyStatusState.value = ProxyServiceStatus(ProxyServiceState.Stopping)
                    },
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
                    proxyStatusProvider = { proxyStatusState.value },
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onNode(hasText("Start proxy") and hasClickAction())
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            startRequests == 1 &&
                composeRule
                    .onAllNodes(hasText("Starting"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        proxyStatusState.value =
            ProxyServiceStatus.running(
                listenHost = "127.0.0.1",
                listenPort = 8080,
                configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                boundRoute = null,
                publicIp = null,
                hasHighSecurityRisk = false,
            )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("Running"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNode(hasText("Stop proxy") and hasClickAction())
            .performClick()
        composeRule
            .onNodeWithText("Confirm proxy service stop")
            .assertIsDisplayed()
        composeRule
            .onNode(hasText("Confirm") and hasClickAction())
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            stopRequests == 1 &&
                composeRule
                    .onAllNodes(hasText("Stopping"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
)
