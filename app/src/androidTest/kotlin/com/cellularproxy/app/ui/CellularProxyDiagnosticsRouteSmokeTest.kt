package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.shared.logging.LogRedactionSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CellularProxyDiagnosticsRouteSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitCloudflareManagementCheckRunsOnlyWhenClicked() {
        val cloudflareManagementProbeCalls = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDiagnosticsRoute(
                    cloudflareManagementApiProbeResultProvider = {
                        cloudflareManagementProbeCalls.incrementAndGet()
                        CloudflareManagementApiProbeResult.Authenticated
                    },
                )
            }
        }

        composeRule
            .onNodeWithText("Run non-Cloudflare checks")
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("5 of 7 checks complete"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, cloudflareManagementProbeCalls.get())

        composeRule
            .onNodeWithText("Run Cloudflare management API")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            cloudflareManagementProbeCalls.get() == 1
        }
        composeRule
            .onNodeWithText("Cloudflare management API authenticated")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun localManagementApiCheckRunsCopiesRedactedTextAndRecordsAudit() {
        val localManagementProbeCalls = AtomicInteger(0)
        val copiedTexts = mutableListOf<String>()
        val auditRecords = mutableListOf<PersistedLogsAuditRecord>()
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDiagnosticsRoute(
                    redactionSecretsProvider = {
                        LogRedactionSecrets(managementApiToken = "management-secret")
                    },
                    localManagementApiProbeResultProvider = {
                        localManagementProbeCalls.incrementAndGet()
                        LocalManagementApiProbeResult.Unauthorized
                    },
                    onCopyDiagnosticsSummaryText = copiedTexts::add,
                    onRecordDiagnosticsAuditAction = auditRecords::add,
                    auditOccurredAtEpochMillisProvider = { 123L },
                )
            }
        }

        assertEquals(0, localManagementProbeCalls.get())

        composeRule
            .onNodeWithText("Run Local management API")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("Local management API unauthorized"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNodeWithText("Copy Local management API")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            copiedTexts.isNotEmpty() &&
                auditRecords.count { record -> record.detail == "action=copy_check check=local_management_api" } == 1
        }

        assertEquals(1, localManagementProbeCalls.get())
        assertEquals(
            listOf(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 123L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Diagnostics run_check",
                    detail = "action=run_check check=local_management_api",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 123L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Diagnostics copy_check",
                    detail = "action=copy_check check=local_management_api",
                ),
            ),
            auditRecords,
        )
        assertEquals("Local management API: warning (unauthorized) - Local management API rejected credentials", copiedTexts.single())
        assertFalse(copiedTexts.single().contains("management-secret"))
    }
}
