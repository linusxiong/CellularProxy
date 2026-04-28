package com.cellularproxy.app.audit

import com.cellularproxy.app.service.ForegroundServiceAuditEvent
import com.cellularproxy.app.service.ForegroundServiceAuditOutcome
import com.cellularproxy.app.service.ForegroundServiceAuditRecord
import com.cellularproxy.app.service.ForegroundServiceCommand
import com.cellularproxy.app.service.ForegroundServiceCommandSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileBackedForegroundServiceAuditLogTest {
    @Test
    fun `records foreground service audit entries durably`() {
        withLogFile { file ->
            val auditLog = FileBackedForegroundServiceAuditLog(file = file)

            auditLog.record(startedRecord(occurredAtEpochMillis = 1_000L))
            auditLog.record(stoppedRecord(occurredAtEpochMillis = 1_001L))

            val reloaded = FileBackedForegroundServiceAuditLog(file = file)

            assertEquals(
                listOf(
                    PersistedForegroundServiceAuditRecord(
                        occurredAtEpochMillis = 1_000L,
                        event = ForegroundServiceAuditEvent.StartRequested,
                        command = ForegroundServiceCommand.Start,
                        source = ForegroundServiceCommandSource.App,
                        outcome = ForegroundServiceAuditOutcome.RuntimeStarted,
                    ),
                    PersistedForegroundServiceAuditRecord(
                        occurredAtEpochMillis = 1_001L,
                        event = ForegroundServiceAuditEvent.NotificationStopRequested,
                        command = ForegroundServiceCommand.Stop,
                        source = ForegroundServiceCommandSource.Notification,
                        outcome = ForegroundServiceAuditOutcome.RuntimeStopped,
                    ),
                ),
                reloaded.readAll(),
            )
        }
    }

    @Test
    fun `retains newest foreground service audit records when max record count is exceeded`() {
        withLogFile { file ->
            val auditLog =
                FileBackedForegroundServiceAuditLog(
                    file = file,
                    maxRecords = 2,
                )

            auditLog.record(startedRecord(occurredAtEpochMillis = 10L))
            auditLog.record(stoppedRecord(occurredAtEpochMillis = 11L))
            auditLog.record(startedRecord(occurredAtEpochMillis = 12L))

            assertEquals(
                listOf(
                    PersistedForegroundServiceAuditRecord(
                        occurredAtEpochMillis = 11L,
                        event = ForegroundServiceAuditEvent.NotificationStopRequested,
                        command = ForegroundServiceCommand.Stop,
                        source = ForegroundServiceCommandSource.Notification,
                        outcome = ForegroundServiceAuditOutcome.RuntimeStopped,
                    ),
                    PersistedForegroundServiceAuditRecord(
                        occurredAtEpochMillis = 12L,
                        event = ForegroundServiceAuditEvent.StartRequested,
                        command = ForegroundServiceCommand.Start,
                        source = ForegroundServiceCommandSource.App,
                        outcome = ForegroundServiceAuditOutcome.RuntimeStarted,
                    ),
                ),
                auditLog.readAll(),
            )
        }
    }

    @Test
    fun `skips malformed foreground service audit lines when reading and compacting`() {
        withLogFile { file ->
            val auditLog = FileBackedForegroundServiceAuditLog(file = file)
            auditLog.record(startedRecord(occurredAtEpochMillis = 20L))
            file.appendText("malformed\n")
            file.appendText("v2\t21\tStartRequested\tStart\tApp\tRuntimeStarted\n")
            file.appendText("v1\tbad-time\tStartRequested\tStart\tApp\tRuntimeStarted\n")
            file.appendText("v1\t22\tUnknownEvent\tStart\tApp\tRuntimeStarted\n")

            assertEquals(
                listOf(
                    PersistedForegroundServiceAuditRecord(
                        occurredAtEpochMillis = 20L,
                        event = ForegroundServiceAuditEvent.StartRequested,
                        command = ForegroundServiceCommand.Start,
                        source = ForegroundServiceCommandSource.App,
                        outcome = ForegroundServiceAuditOutcome.RuntimeStarted,
                    ),
                ),
                auditLog.readAll(),
            )

            auditLog.record(stoppedRecord(occurredAtEpochMillis = 21L))

            assertEquals(2, file.readLines().size)
        }
    }

    @Test
    fun `rejects invalid foreground service audit limits`() {
        withLogFile { file ->
            assertFailsWith<IllegalArgumentException> {
                FileBackedForegroundServiceAuditLog(file = file, maxRecords = 0)
            }
        }
    }

    private fun startedRecord(occurredAtEpochMillis: Long): ForegroundServiceAuditRecord = ForegroundServiceAuditRecord(
        occurredAtEpochMillis = occurredAtEpochMillis,
        event = ForegroundServiceAuditEvent.StartRequested,
        command = ForegroundServiceCommand.Start,
        source = ForegroundServiceCommandSource.App,
        outcome = ForegroundServiceAuditOutcome.RuntimeStarted,
    )

    private fun stoppedRecord(occurredAtEpochMillis: Long): ForegroundServiceAuditRecord = ForegroundServiceAuditRecord(
        occurredAtEpochMillis = occurredAtEpochMillis,
        event = ForegroundServiceAuditEvent.NotificationStopRequested,
        command = ForegroundServiceCommand.Stop,
        source = ForegroundServiceCommandSource.Notification,
        outcome = ForegroundServiceAuditOutcome.RuntimeStopped,
    )

    private fun withLogFile(block: (File) -> Unit) {
        val directory = createTempDirectory(prefix = "cellularproxy-foreground-service-audit")
        val directoryFile = directory.toFile()
        try {
            block(File(directoryFile, "foreground-service.audit"))
        } finally {
            directoryFile.deleteRecursively()
        }
    }
}
