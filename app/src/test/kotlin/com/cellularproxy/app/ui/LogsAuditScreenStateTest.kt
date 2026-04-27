package com.cellularproxy.app.ui

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LogsAuditScreenStateTest {
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
