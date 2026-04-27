package com.cellularproxy.app.ui

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogsAuditScreenStateTest {
    @Test
    fun `controller selection updates state and copy selected emits redacted payload`() {
        val controller =
            LogsAuditScreenController(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "safe",
                            category = LogsAuditScreenCategory.AppRuntime,
                            severity = LogsAuditScreenSeverity.Info,
                            occurredAtEpochMillis = 100,
                            title = "Runtime started",
                            detail = "No issue",
                        ),
                        LogsAuditScreenInputRow(
                            id = "secret",
                            category = LogsAuditScreenCategory.ManagementApi,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Management failed for secret-token",
                            detail = "Authorization: Bearer secret-token",
                        ),
                    ),
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "secret-token",
                    ),
            )

        controller.handle(LogsAuditScreenEvent.SelectRecord("secret"))
        controller.handle(LogsAuditScreenEvent.CopySelectedRecord)

        assertEquals("secret", controller.state.selectedRow?.id)
        assertEquals(
            listOf(
                LogsAuditScreenEffect.CopyText(
                    "Management API | Failed | 200 | Management failed for [REDACTED]\nAuthorization: [REDACTED]",
                ),
            ),
            controller.consumeEffects(),
        )
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller filter update rebuilds rows and clears selection when selected row is no longer visible`() {
        val controller =
            LogsAuditScreenController(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "warning",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Warning,
                            occurredAtEpochMillis = 100,
                            title = "Limit warning",
                            detail = "Near connection limit",
                        ),
                        LogsAuditScreenInputRow(
                            id = "failed",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Proxy failed",
                            detail = "Bind failed",
                        ),
                    ),
            )

        controller.handle(LogsAuditScreenEvent.SelectRecord("warning"))
        controller.handle(
            LogsAuditScreenEvent.UpdateFilter(
                LogsAuditScreenFilter(
                    severity = LogsAuditScreenSeverity.Failed,
                ),
            ),
        )

        assertNull(controller.state.selectedRow)
        assertEquals(listOf("failed"), controller.state.rows.map(LogsAuditScreenRow::id))
    }

    @Test
    fun `controller selection preserves the current filter`() {
        val controller =
            LogsAuditScreenController(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "info",
                            category = LogsAuditScreenCategory.AppRuntime,
                            severity = LogsAuditScreenSeverity.Info,
                            occurredAtEpochMillis = 100,
                            title = "Runtime started",
                            detail = "No issue",
                        ),
                        LogsAuditScreenInputRow(
                            id = "failed",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Proxy failed",
                            detail = "Bind failed",
                        ),
                    ),
            )

        controller.handle(
            LogsAuditScreenEvent.UpdateFilter(
                LogsAuditScreenFilter(
                    severity = LogsAuditScreenSeverity.Failed,
                ),
            ),
        )
        controller.handle(LogsAuditScreenEvent.SelectRecord("failed"))

        assertEquals(LogsAuditScreenSeverity.Failed, controller.state.filter.severity)
        assertEquals(listOf("failed"), controller.state.rows.map(LogsAuditScreenRow::id))
        assertEquals("failed", controller.state.selectedRow?.id)
    }

    @Test
    fun `controller copy summary and export emit state derived redacted effects`() {
        val controller =
            LogsAuditScreenController(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "secret",
                            category = LogsAuditScreenCategory.CloudflareTunnel,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Tunnel failed for secret-token",
                            detail = "edge token=secret-token",
                        ),
                    ),
                secrets =
                    LogRedactionSecrets(
                        cloudflareTunnelToken = "secret-token",
                    ),
                exportSupported = true,
                exportGeneratedAtEpochMillis = 300,
            )

        controller.handle(LogsAuditScreenEvent.CopyFilteredSummary)
        controller.handle(LogsAuditScreenEvent.ExportRedactedBundle)

        val effects = controller.consumeEffects()
        assertEquals(2, effects.size)
        assertFalse((effects[0] as LogsAuditScreenEffect.CopyText).text.contains("secret-token"))
        val export = (effects[1] as LogsAuditScreenEffect.ExportBundle).bundle
        assertEquals("cellularproxy-logs-audit-300.txt", export.fileName)
        assertFalse(export.text.contains("secret-token"))
    }

    @Test
    fun `selected record copy payload is derived from redacted selected row`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "selected",
                            category = LogsAuditScreenCategory.ManagementApi,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Management call failed for secret-token",
                            detail = "Authorization: Bearer secret-token\n/api/status?token=secret-token",
                        ),
                    ),
                selectedRowId = "selected",
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "secret-token",
                    ),
            )

        assertEquals(
            "Management API | Failed | 200 | Management call failed for [REDACTED]\nAuthorization: [REDACTED]\n/api/status?[REDACTED]",
            state.copyableSelectedRecord,
        )
        assertFalse(state.copyableSelectedRecord.orEmpty().contains("secret-token"))
    }

    @Test
    fun `selected record copy payload is absent when selected row is filtered out`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "filtered-out",
                            category = LogsAuditScreenCategory.AppRuntime,
                            severity = LogsAuditScreenSeverity.Info,
                            occurredAtEpochMillis = 100,
                            title = "Runtime started",
                            detail = "No issue",
                        ),
                    ),
                selectedRowId = "filtered-out",
                filter =
                    LogsAuditScreenFilter(
                        severity = LogsAuditScreenSeverity.Failed,
                    ),
            )

        assertNull(state.copyableSelectedRecord)
    }
}
