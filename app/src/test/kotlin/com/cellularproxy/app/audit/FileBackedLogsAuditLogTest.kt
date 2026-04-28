package com.cellularproxy.app.audit

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileBackedLogsAuditLogTest {
    @Test
    fun `records generic logs audit entries durably`() {
        withLogFile { file ->
            val log = FileBackedLogsAuditLog(file = file)

            log.record(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 1_000L,
                    category = LogsAuditRecordCategory.ProxyServer,
                    severity = LogsAuditRecordSeverity.Warning,
                    title = "Connection limit near capacity",
                    detail = "active=60 max=64",
                ),
            )
            log.record(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 1_001L,
                    category = LogsAuditRecordCategory.CloudflareTunnel,
                    severity = LogsAuditRecordSeverity.Failed,
                    title = "Tunnel degraded",
                    detail = "edge=iad error=token-secret",
                ),
            )

            val reloaded = FileBackedLogsAuditLog(file = file)

            assertEquals(
                listOf(
                    PersistedLogsAuditRecord(
                        occurredAtEpochMillis = 1_000L,
                        category = LogsAuditRecordCategory.ProxyServer,
                        severity = LogsAuditRecordSeverity.Warning,
                        title = "Connection limit near capacity",
                        detail = "active=60 max=64",
                    ),
                    PersistedLogsAuditRecord(
                        occurredAtEpochMillis = 1_001L,
                        category = LogsAuditRecordCategory.CloudflareTunnel,
                        severity = LogsAuditRecordSeverity.Failed,
                        title = "Tunnel degraded",
                        detail = "edge=iad error=token-secret",
                    ),
                ),
                reloaded.readAll(),
            )
        }
    }

    @Test
    fun `retains newest generic logs audit records and skips malformed lines`() {
        withLogFile { file ->
            val log = FileBackedLogsAuditLog(file = file, maxRecords = 2)

            log.record(record(occurredAtEpochMillis = 10L, category = LogsAuditRecordCategory.ProxyServer))
            log.record(record(occurredAtEpochMillis = 11L, category = LogsAuditRecordCategory.Rotation))
            file.appendText("malformed\n")
            file.appendText("v2\t12\tRotation\tWarning\ttitle\tdetail\n")
            file.appendText("v1\tbad-time\tRotation\tWarning\ttitle\tdetail\n")
            file.appendText("v1\t12\tUnknown\tWarning\ttitle\tdetail\n")
            file.appendText("v1\t13\tRotation\tUnknown\ttitle\tdetail\n")
            log.record(record(occurredAtEpochMillis = 12L, category = LogsAuditRecordCategory.Audit))

            assertEquals(
                listOf(
                    record(occurredAtEpochMillis = 11L, category = LogsAuditRecordCategory.Rotation),
                    record(occurredAtEpochMillis = 12L, category = LogsAuditRecordCategory.Audit),
                ),
                log.readAll(),
            )
            assertEquals(2, file.readLines().size)
        }
    }

    @Test
    fun `rejects invalid generic logs audit records and limits`() {
        withLogFile { file ->
            assertFailsWith<IllegalArgumentException> {
                FileBackedLogsAuditLog(file = file, maxRecords = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                record(occurredAtEpochMillis = -1L, category = LogsAuditRecordCategory.ProxyServer)
            }
            assertFailsWith<IllegalArgumentException> {
                record(
                    occurredAtEpochMillis = 1L,
                    category = LogsAuditRecordCategory.ProxyServer,
                    title = "",
                )
            }
        }
    }

    private fun record(
        occurredAtEpochMillis: Long,
        category: LogsAuditRecordCategory,
        title: String = "title",
    ): PersistedLogsAuditRecord = PersistedLogsAuditRecord(
        occurredAtEpochMillis = occurredAtEpochMillis,
        category = category,
        severity = LogsAuditRecordSeverity.Warning,
        title = title,
        detail = "detail",
    )

    private fun withLogFile(block: (File) -> Unit) {
        val directory = createTempDirectory(prefix = "cellularproxy-logs-audit")
        val directoryFile = directory.toFile()
        try {
            block(File(directoryFile, "logs.audit"))
        } finally {
            directoryFile.deleteRecursively()
        }
    }
}
