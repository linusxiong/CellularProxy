package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.rotation.RotationStatus
import org.junit.Rule
import org.junit.Test

class CellularProxyAppNavigationSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topLevelNavigationShowsEveryOperatorDestination() {
        composeRule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                Scaffold(
                    bottomBar = {
                        CellularProxyNavigationBar(navController)
                    },
                ) { contentPadding ->
                    CellularProxyNavigationHost(
                        navController = navController,
                        onStartProxyService = {},
                        onStopProxyService = {},
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
                        currentPublicIpProvider = { null },
                        onCopyText = {},
                        onExportLogsAuditBundle = {},
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Service state").assertIsDisplayed()

        assertDestinationRenders("Settings", "Listen host")
        assertDestinationRenders("Cloudflare", "Tunnel enabled")
        assertDestinationRenders("Rotation", "Root availability")
        assertDestinationRenders("Diagnostics", "Root availability")
        assertDestinationRenders("Logs/Audit", "No log or audit records match the current filters.")
    }

    private fun assertDestinationRenders(
        destinationLabel: String,
        expectedScreenText: String,
    ) {
        composeRule.onNode(hasText(destinationLabel) and hasClickAction()).performClick()
        composeRule.onNodeWithText(expectedScreenText).assertExists()
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
)
