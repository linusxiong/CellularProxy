package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.shared.logging.LogRedactionSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CellularProxyLogsAuditRouteSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routeFiltersCopiesAndExportsRedactedRecords() {
        val copiedText = mutableListOf<String>()
        val exportedBundles = mutableListOf<LogsAuditScreenExportBundle>()
        val auditRecords = mutableListOf<PersistedLogsAuditRecord>()

        composeRule.setContent {
            MaterialTheme {
                CellularProxyLogsAuditRoute(
                    logsAuditRowsProvider = {
                        listOf(
                            LogsAuditScreenInputRow(
                                id = "management-secret",
                                category = LogsAuditScreenCategory.ManagementApi,
                                severity = LogsAuditScreenSeverity.Failed,
                                occurredAtEpochMillis = 200L,
                                title = "Management call failed for secret-token",
                                detail = "Authorization: Bearer secret-token\n/api/status?token=secret-token",
                            ),
                            LogsAuditScreenInputRow(
                                id = "proxy-warning",
                                category = LogsAuditScreenCategory.ProxyServer,
                                severity = LogsAuditScreenSeverity.Warning,
                                occurredAtEpochMillis = 100L,
                                title = "Proxy warning",
                                detail = "Connection closed",
                            ),
                        )
                    },
                    redactionSecretsProvider = {
                        LogRedactionSecrets(
                            managementApiToken = "secret-token",
                        )
                    },
                    onCopyLogsAuditText = copiedText::add,
                    onExportLogsAuditBundle = exportedBundles::add,
                    onRecordLogsAuditAction = auditRecords::add,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("2 of 2 records"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithText("Management call failed for [REDACTED]")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Search logs")
            .performScrollTo()
            .performTextInput("secret-token")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("1 of 2 records"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithText("Management call failed for [REDACTED]")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Copy filtered summary")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            copiedText.isNotEmpty()
        }
        assertTrue(copiedText.single().contains("Management call failed for [REDACTED]"))
        assertFalse(copiedText.single().contains("secret-token"))

        composeRule
            .onNodeWithText("Export redacted bundle")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            exportedBundles.isNotEmpty()
        }
        val exportBundle = exportedBundles.single()
        assertEquals(1, exportBundle.rowCount)
        assertTrue(exportBundle.text.contains("Management call failed for [REDACTED]"))
        assertFalse(exportBundle.text.contains("secret-token"))
        assertEquals(
            listOf("Logs/Audit copy_filtered_summary", "Logs/Audit export_redacted_bundle"),
            auditRecords.map(PersistedLogsAuditRecord::title),
        )
        assertEquals(
            listOf(LogsAuditRecordCategory.Audit, LogsAuditRecordCategory.Audit),
            auditRecords.map(PersistedLogsAuditRecord::category),
        )
        assertEquals(
            listOf(LogsAuditRecordSeverity.Info, LogsAuditRecordSeverity.Info),
            auditRecords.map(PersistedLogsAuditRecord::severity),
        )
        assertTrue(auditRecords.joinToString(separator = "\n").contains("rowCount=1"))
        assertFalse(auditRecords.joinToString(separator = "\n").contains("secret-token"))
    }
}
