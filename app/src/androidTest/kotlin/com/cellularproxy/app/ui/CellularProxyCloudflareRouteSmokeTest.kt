package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CellularProxyCloudflareRouteSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CellularProxyComposeTestActivity>()

    @Test
    fun routeRunsExplicitManagementTunnelTestAndCopiesRedactedDiagnostics() {
        val managementRoundTrip = mutableStateOf<String?>(null)
        val managementRoundTripVersion = mutableLongStateOf(0L)
        val copiedDiagnostics = mutableListOf<String>()
        val auditRecords = mutableListOf<PersistedLogsAuditRecord>()
        var managementTunnelTestCalls = 0

        composeRule.setContent {
            MaterialTheme {
                CellularProxyCloudflareRoute(
                    configProvider = {
                        AppConfig.default().copy(
                            cloudflare =
                                CloudflareConfig(
                                    enabled = true,
                                    tunnelTokenPresent = true,
                                    managementHostnameLabel =
                                        "https://operator:hostname-secret@management.example.test/private?token=query-secret",
                                ),
                        )
                    },
                    tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                    edgeSessionSummaryProvider = { "edge connected for token=secret-token" },
                    managementApiRoundTripProvider = { managementRoundTrip.value },
                    managementApiRoundTripVersionProvider = { managementRoundTripVersion.longValue },
                    redactionSecretsProvider = {
                        LogRedactionSecrets(
                            cloudflareTunnelToken = "secret-token",
                        )
                    },
                    onTestManagementTunnel = {
                        managementTunnelTestCalls += 1
                        managementRoundTrip.value = "HTTP 200 OK for token=secret-token"
                        managementRoundTripVersion.longValue += 1
                    },
                    onCopyDiagnosticsText = copiedDiagnostics::add,
                    onRecordCloudflareAuditAction = auditRecords::add,
                    auditOccurredAtEpochMillisProvider = { 123L },
                )
            }
        }

        composeRule
            .onNodeWithText("Connected")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("https://management.example.test")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Test management tunnel")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            managementTunnelTestCalls == 1 &&
                composeRule
                    .onAllNodes(hasText("HTTP 200 OK for token=[REDACTED]"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        composeRule
            .onNodeWithText("Copy diagnostics")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            copiedDiagnostics.isNotEmpty()
        }
        val copiedText = copiedDiagnostics.single()
        assertTrue(copiedText.contains("Tunnel lifecycle: Connected"))
        assertTrue(copiedText.contains("Management API round trip: HTTP 200 OK for token=[REDACTED]"))
        assertFalse(copiedText.contains("secret-token"))
        assertFalse(copiedText.contains("hostname-secret"))
        assertFalse(copiedText.contains("query-secret"))
        assertEquals(
            listOf("Cloudflare test_management_tunnel", "Cloudflare copy_diagnostics"),
            auditRecords.map(PersistedLogsAuditRecord::title),
        )
    }
}
