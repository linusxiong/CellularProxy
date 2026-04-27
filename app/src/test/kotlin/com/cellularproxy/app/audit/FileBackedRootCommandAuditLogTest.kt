package com.cellularproxy.app.audit

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.root.RootCommandResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileBackedRootCommandAuditLogTest {
    @Test
    fun `records root audit entries durably with redacted fields and timestamps`() {
        withLogFile { file ->
            val auditLog =
                FileBackedRootCommandAuditLog(
                    file = file,
                    clock = incrementingClock(start = 1_000L),
                )

            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.MobileDataDisable))
            auditLog.record(
                RootCommandAuditRecord.completed(
                    RootCommandResult.completed(
                        category = RootCommandCategory.MobileDataDisable,
                        exitCode = 1,
                        stdout = "management-token",
                        stderr = "Proxy-Authorization: Basic abc",
                        secrets = LogRedactionSecrets(managementApiToken = "management-token"),
                    ),
                ),
            )

            val reloaded = FileBackedRootCommandAuditLog(file = file)

            assertEquals(
                listOf(
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 1_000L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.MobileDataDisable,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 1_001L,
                        phase = RootCommandAuditPhase.Completed,
                        category = RootCommandCategory.MobileDataDisable,
                        outcome = RootCommandOutcome.Failure,
                        exitCode = 1,
                        stdout = "[REDACTED]",
                        stderr = "Proxy-Authorization: [REDACTED]",
                    ),
                ),
                reloaded.readAll(),
            )
        }
    }

    @Test
    fun `retains newest audit records when max record count is exceeded`() {
        withLogFile { file ->
            val auditLog =
                FileBackedRootCommandAuditLog(
                    file = file,
                    clock = incrementingClock(start = 10L),
                    maxRecords = 2,
                )

            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck))
            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.MobileDataDisable))
            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.MobileDataEnable))

            assertEquals(
                listOf(
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 11L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.MobileDataDisable,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 12L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.MobileDataEnable,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                ),
                auditLog.readAll(),
            )
        }
    }

    @Test
    fun `skips malformed persisted audit lines when reading and compacting`() {
        withLogFile { file ->
            val auditLog =
                FileBackedRootCommandAuditLog(
                    file = file,
                    clock = incrementingClock(start = 20L),
                )
            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck))
            file.appendText("malformed\n")
            file.appendText("v2\t20\tStarted\tRootAvailabilityCheck\t-\t-\t-\t-\n")
            file.appendText("v1\tbad-time\tStarted\tRootAvailabilityCheck\t-\t-\t-\t-\n")
            file.appendText("v1\t21\tStarted\tUnknownCategory\t-\t-\t-\t-\n")
            file.appendText("v1\t22\tStarted\tRootAvailabilityCheck\t-\t-\t%%%\t-\n")

            assertEquals(
                listOf(
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 20L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.RootAvailabilityCheck,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                ),
                auditLog.readAll(),
            )

            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.MobileDataDisable))

            assertEquals(
                listOf(
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 20L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.RootAvailabilityCheck,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 21L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.MobileDataDisable,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                ),
                auditLog.readAll(),
            )
            assertEquals(2, file.readLines().size)
        }
    }

    @Test
    fun `failed file replacement preserves existing audit log`() {
        withLogFile { file ->
            val auditLog =
                FileBackedRootCommandAuditLog(
                    file = file,
                    clock = incrementingClock(start = 30L),
                )
            auditLog.record(RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck))
            val originalContents = file.readText()
            val failingAuditLog =
                FileBackedRootCommandAuditLog(
                    file = file,
                    clock = incrementingClock(start = 31L),
                    replaceFile = { _, _ -> throw java.io.IOException("disk full") },
                )

            assertFailsWith<java.io.IOException> {
                failingAuditLog.record(RootCommandAuditRecord.started(RootCommandCategory.MobileDataDisable))
            }

            assertEquals(originalContents, file.readText())
            assertEquals(
                listOf(
                    PersistedRootCommandAuditRecord(
                        occurredAtEpochMillis = 30L,
                        phase = RootCommandAuditPhase.Started,
                        category = RootCommandCategory.RootAvailabilityCheck,
                        outcome = null,
                        exitCode = null,
                        stdout = null,
                        stderr = null,
                    ),
                ),
                auditLog.readAll(),
            )
        }
    }

    @Test
    fun `rejects non-positive max record limits`() {
        withLogFile { file ->
            assertFailsWith<IllegalArgumentException> {
                FileBackedRootCommandAuditLog(file = file, maxRecords = 0)
            }
        }
    }

    private fun withLogFile(block: (File) -> Unit) {
        val directory = createTempDirectory(prefix = "cellularproxy-root-audit")
        val directoryFile = directory.toFile()
        try {
            block(File(directoryFile, "root-commands.audit"))
        } finally {
            directoryFile.deleteRecursively()
        }
    }

    private fun incrementingClock(start: Long): () -> Long {
        var next = start
        return { next++ }
    }
}
