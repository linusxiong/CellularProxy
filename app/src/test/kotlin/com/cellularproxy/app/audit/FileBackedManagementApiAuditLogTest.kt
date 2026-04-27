package com.cellularproxy.app.audit

import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileBackedManagementApiAuditLogTest {
    @Test
    fun `records management api audit entries durably with timestamps`() {
        withLogFile { file ->
            val auditLog = FileBackedManagementApiAuditLog(
                file = file,
                clock = incrementingClock(start = 1_000L),
            )

            auditLog.record(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.RotateMobileData,
                    outcome = ManagementApiAuditOutcome.Responded,
                    statusCode = 202,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                ),
            )
            auditLog.record(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiAuditOutcome.HandlerFailed,
                    statusCode = null,
                    disposition = null,
                ),
            )

            val reloaded = FileBackedManagementApiAuditLog(file = file)

            assertEquals(
                listOf(
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 1_000L,
                        operation = ManagementApiOperation.RotateMobileData,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 1_001L,
                        operation = ManagementApiOperation.ServiceStop,
                        outcome = ManagementApiAuditOutcome.HandlerFailed,
                        statusCode = null,
                        disposition = null,
                    ),
                ),
                reloaded.readAll(),
            )
        }
    }

    @Test
    fun `retains newest management audit records when max record count is exceeded`() {
        withLogFile { file ->
            val auditLog = FileBackedManagementApiAuditLog(
                file = file,
                clock = incrementingClock(start = 10L),
                maxRecords = 2,
            )

            auditLog.record(record(ManagementApiOperation.CloudflareStart))
            auditLog.record(record(ManagementApiOperation.CloudflareStop))
            auditLog.record(record(ManagementApiOperation.ServiceStop))

            assertEquals(
                listOf(
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 11L,
                        operation = ManagementApiOperation.CloudflareStop,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 12L,
                        operation = ManagementApiOperation.ServiceStop,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                ),
                auditLog.readAll(),
            )
        }
    }

    @Test
    fun `skips malformed persisted management audit lines when reading and compacting`() {
        withLogFile { file ->
            val auditLog = FileBackedManagementApiAuditLog(
                file = file,
                clock = incrementingClock(start = 20L),
            )
            auditLog.record(record(ManagementApiOperation.RotateAirplaneMode))
            file.appendText("malformed\n")
            file.appendText("v2\t20\tRotateAirplaneMode\tResponded\t202\tRouted\n")
            file.appendText("v1\tbad-time\tRotateAirplaneMode\tResponded\t202\tRouted\n")
            file.appendText("v1\t21\tUnknownOperation\tResponded\t202\tRouted\n")
            file.appendText("v1\t22\tRotateAirplaneMode\tUnknownOutcome\t202\tRouted\n")
            file.appendText("v1\t23\tRotateAirplaneMode\tResponded\tbad-status\tRouted\n")
            file.appendText("v1\t24\tRotateAirplaneMode\tResponded\t999\tRouted\n")

            assertEquals(
                listOf(
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 20L,
                        operation = ManagementApiOperation.RotateAirplaneMode,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                ),
                auditLog.readAll(),
            )

            auditLog.record(record(ManagementApiOperation.ServiceStop))

            assertEquals(
                listOf(
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 20L,
                        operation = ManagementApiOperation.RotateAirplaneMode,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 21L,
                        operation = ManagementApiOperation.ServiceStop,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                ),
                auditLog.readAll(),
            )
            assertEquals(2, file.readLines().size)
        }
    }

    @Test
    fun `failed management audit replacement preserves existing log`() {
        withLogFile { file ->
            val auditLog = FileBackedManagementApiAuditLog(
                file = file,
                clock = incrementingClock(start = 30L),
            )
            auditLog.record(record(ManagementApiOperation.CloudflareStart))
            val originalContents = file.readText()
            val failingAuditLog = FileBackedManagementApiAuditLog(
                file = file,
                clock = incrementingClock(start = 31L),
                replaceFile = { _, _ -> throw java.io.IOException("disk full") },
            )

            assertFailsWith<java.io.IOException> {
                failingAuditLog.record(record(ManagementApiOperation.CloudflareStop))
            }

            assertEquals(originalContents, file.readText())
            assertEquals(
                listOf(
                    PersistedManagementApiAuditRecord(
                        occurredAtEpochMillis = 30L,
                        operation = ManagementApiOperation.CloudflareStart,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 202,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                ),
                auditLog.readAll(),
            )
        }
    }

    @Test
    fun `rejects invalid management audit records and limits`() {
        withLogFile { file ->
            assertFailsWith<IllegalArgumentException> {
                FileBackedManagementApiAuditLog(file = file, maxRecords = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiAuditOutcome.Responded,
                    statusCode = 99,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiAuditOutcome.Responded,
                    statusCode = null,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiAuditOutcome.HandlerFailed,
                    statusCode = null,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiAuditOutcome.RouteRejected,
                    statusCode = 404,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                )
            }
        }
    }

    @Test
    fun `atomic replacement falls back when atomic move is unsupported`() {
        withLogFile { file ->
            var nonAtomicMoveUsed = false

            replaceManagementAuditFileAtomically(
                destination = file,
                contents = "contents\n",
                moveAtomically = { _, _ ->
                    throw AtomicMoveNotSupportedException("source", "target", "unsupported")
                },
                moveNonAtomically = { source, target ->
                    nonAtomicMoveUsed = true
                    java.nio.file.Files.move(source, target)
                },
            )

            assertEquals(true, nonAtomicMoveUsed)
            assertEquals("contents\n", file.readText())
        }
    }

    private fun record(operation: ManagementApiOperation): ManagementApiAuditRecord =
        ManagementApiAuditRecord(
            operation = operation,
            outcome = ManagementApiAuditOutcome.Responded,
            statusCode = 202,
            disposition = ManagementApiStreamExchangeDisposition.Routed,
        )

    private fun withLogFile(block: (File) -> Unit) {
        val directory = createTempDirectory(prefix = "cellularproxy-management-audit")
        val directoryFile = directory.toFile()
        try {
            block(File(directoryFile, "management-api.audit"))
        } finally {
            directoryFile.deleteRecursively()
        }
    }

    private fun incrementingClock(start: Long): () -> Long {
        var next = start
        return { next++ }
    }
}
