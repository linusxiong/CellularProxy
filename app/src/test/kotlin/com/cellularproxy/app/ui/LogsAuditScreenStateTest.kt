package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.PersistedForegroundServiceAuditRecord
import com.cellularproxy.app.audit.PersistedManagementApiAuditRecord
import com.cellularproxy.app.audit.PersistedRootCommandAuditRecord
import com.cellularproxy.app.service.ForegroundServiceAuditEvent
import com.cellularproxy.app.service.ForegroundServiceAuditOutcome
import com.cellularproxy.app.service.ForegroundServiceCommand
import com.cellularproxy.app.service.ForegroundServiceCommandSource
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogsAuditScreenStateTest {
    @Test
    fun `persisted management and root audit records map to screen input rows`() {
        val rows =
            logsAuditScreenRowsFromPersistedAuditRecords(
                managementRecords =
                    listOf(
                        PersistedManagementApiAuditRecord(
                            occurredAtEpochMillis = 100,
                            operation = ManagementApiOperation.CloudflareStart,
                            outcome = ManagementApiAuditOutcome.Responded,
                            statusCode = 202,
                            disposition = ManagementApiStreamExchangeDisposition.Routed,
                        ),
                        PersistedManagementApiAuditRecord(
                            occurredAtEpochMillis = 110,
                            operation = ManagementApiOperation.RotateMobileData,
                            outcome = ManagementApiAuditOutcome.HandlerFailed,
                            statusCode = null,
                            disposition = null,
                        ),
                    ),
                rootRecords =
                    listOf(
                        PersistedRootCommandAuditRecord(
                            occurredAtEpochMillis = 120,
                            phase = RootCommandAuditPhase.Started,
                            category = RootCommandCategory.RootAvailabilityCheck,
                            outcome = null,
                            exitCode = null,
                            stdout = null,
                            stderr = null,
                        ),
                        PersistedRootCommandAuditRecord(
                            occurredAtEpochMillis = 130,
                            phase = RootCommandAuditPhase.Completed,
                            category = RootCommandCategory.MobileDataEnable,
                            outcome = RootCommandOutcome.Failure,
                            exitCode = 1,
                            stdout = "token=secret-token",
                            stderr = "failed",
                        ),
                    ),
                foregroundServiceRecords =
                    listOf(
                        PersistedForegroundServiceAuditRecord(
                            occurredAtEpochMillis = 140,
                            event = ForegroundServiceAuditEvent.StartRequested,
                            command = ForegroundServiceCommand.Start,
                            source = ForegroundServiceCommandSource.App,
                            outcome = ForegroundServiceAuditOutcome.RuntimeStarted,
                        ),
                    ),
            )

        assertEquals(
            listOf(
                LogsAuditScreenInputRow(
                    id = "management-api-0-100-CloudflareStart-Responded",
                    category = LogsAuditScreenCategory.ManagementApi,
                    severity = LogsAuditScreenSeverity.Info,
                    occurredAtEpochMillis = 100,
                    title = "Management API CloudflareStart responded",
                    detail = "status=202 disposition=Routed",
                ),
                LogsAuditScreenInputRow(
                    id = "management-api-1-110-RotateMobileData-HandlerFailed",
                    category = LogsAuditScreenCategory.ManagementApi,
                    severity = LogsAuditScreenSeverity.Failed,
                    occurredAtEpochMillis = 110,
                    title = "Management API RotateMobileData handler failed",
                    detail = "status=none disposition=none",
                ),
                LogsAuditScreenInputRow(
                    id = "root-command-0-120-RootAvailabilityCheck-Started",
                    category = LogsAuditScreenCategory.RootCommands,
                    severity = LogsAuditScreenSeverity.Info,
                    occurredAtEpochMillis = 120,
                    title = "Root command RootAvailabilityCheck started",
                    detail = "outcome=none exitCode=none stdout=none stderr=none",
                ),
                LogsAuditScreenInputRow(
                    id = "root-command-1-130-MobileDataEnable-Completed",
                    category = LogsAuditScreenCategory.RootCommands,
                    severity = LogsAuditScreenSeverity.Failed,
                    occurredAtEpochMillis = 130,
                    title = "Root command MobileDataEnable completed",
                    detail = "outcome=Failure exitCode=1 stdout=token=secret-token stderr=failed",
                ),
                LogsAuditScreenInputRow(
                    id = "foreground-service-0-140-StartRequested-RuntimeStarted",
                    category = LogsAuditScreenCategory.AppRuntime,
                    severity = LogsAuditScreenSeverity.Info,
                    occurredAtEpochMillis = 140,
                    title = "Foreground service StartRequested RuntimeStarted",
                    detail = "command=Start source=App",
                ),
            ),
            rows,
        )
    }

    @Test
    fun `persisted audit row ids include read order to disambiguate timestamp collisions`() {
        val duplicateManagementRecord =
            PersistedManagementApiAuditRecord(
                occurredAtEpochMillis = 100,
                operation = ManagementApiOperation.CloudflareStart,
                outcome = ManagementApiAuditOutcome.Responded,
                statusCode = 202,
                disposition = ManagementApiStreamExchangeDisposition.Routed,
            )
        val duplicateRootRecord =
            PersistedRootCommandAuditRecord(
                occurredAtEpochMillis = 200,
                phase = RootCommandAuditPhase.Completed,
                category = RootCommandCategory.MobileDataEnable,
                outcome = RootCommandOutcome.Success,
                exitCode = 0,
                stdout = null,
                stderr = null,
            )

        val rows =
            logsAuditScreenRowsFromPersistedAuditRecords(
                managementRecords = listOf(duplicateManagementRecord, duplicateManagementRecord),
                rootRecords = listOf(duplicateRootRecord, duplicateRootRecord),
            )
        val controller = LogsAuditScreenController(rows = rows)

        controller.handle(LogsAuditScreenEvent.SelectRecord("management-api-1-100-CloudflareStart-Responded"))

        assertEquals(rows.map(LogsAuditScreenInputRow::id).distinct(), rows.map(LogsAuditScreenInputRow::id))
        assertEquals("management-api-1-100-CloudflareStart-Responded", controller.state.selectedRow?.id)
    }

    @Test
    fun `persisted generic log records map to screen input rows`() {
        val rows =
            logsAuditScreenRowsFromPersistedAuditRecords(
                managementRecords = emptyList(),
                rootRecords = emptyList(),
                genericRecords =
                    listOf(
                        PersistedLogsAuditRecord(
                            occurredAtEpochMillis = 210,
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Warning,
                            title = "Connection limit near capacity",
                            detail = "active=60 max=64",
                        ),
                        PersistedLogsAuditRecord(
                            occurredAtEpochMillis = 220,
                            category = LogsAuditScreenCategory.CloudflareTunnel,
                            severity = LogsAuditScreenSeverity.Failed,
                            title = "Tunnel degraded",
                            detail = "edge=iad error=token-secret",
                        ),
                    ),
            )

        assertEquals(
            listOf(
                LogsAuditScreenInputRow(
                    id = "generic-log-0-210-ProxyServer-Warning",
                    category = LogsAuditScreenCategory.ProxyServer,
                    severity = LogsAuditScreenSeverity.Warning,
                    occurredAtEpochMillis = 210,
                    title = "Connection limit near capacity",
                    detail = "active=60 max=64",
                ),
                LogsAuditScreenInputRow(
                    id = "generic-log-1-220-CloudflareTunnel-Failed",
                    category = LogsAuditScreenCategory.CloudflareTunnel,
                    severity = LogsAuditScreenSeverity.Failed,
                    occurredAtEpochMillis = 220,
                    title = "Tunnel degraded",
                    detail = "edge=iad error=token-secret",
                ),
            ),
            rows,
        )
    }

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
    fun `state redacts all configured secrets from rows copy summary and export bundle`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "secret-row",
                            category = LogsAuditScreenCategory.ManagementApi,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Failed for management-secret",
                            detail =
                                "proxy=user:proxy-password " +
                                    "cloudflare=tunnel-secret Authorization: Bearer management-secret",
                        ),
                    ),
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "management-secret",
                        proxyCredential = "user:proxy-password",
                        cloudflareTunnelToken = "tunnel-secret",
                    ),
                exportSupported = true,
                exportGeneratedAtEpochMillis = 300,
            )

        val combinedOutput =
            listOf(
                state.rows.single().title,
                state.rows.single().detail,
                state.copyableFilteredSummary,
                state.exportBundle?.text.orEmpty(),
            ).joinToString(separator = "\n")

        assertFalse(combinedOutput.contains("management-secret"))
        assertFalse(combinedOutput.contains("user:proxy-password"))
        assertFalse(combinedOutput.contains("tunnel-secret"))
        assertTrue(combinedOutput.contains("[REDACTED]"))
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
