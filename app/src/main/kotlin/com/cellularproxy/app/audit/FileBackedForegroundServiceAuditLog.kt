package com.cellularproxy.app.audit

import android.content.Context
import com.cellularproxy.app.service.ForegroundServiceAuditEvent
import com.cellularproxy.app.service.ForegroundServiceAuditOutcome
import com.cellularproxy.app.service.ForegroundServiceAuditRecord
import com.cellularproxy.app.service.ForegroundServiceCommand
import com.cellularproxy.app.service.ForegroundServiceCommandSource
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class PersistedForegroundServiceAuditRecord(
    val occurredAtEpochMillis: Long,
    val event: ForegroundServiceAuditEvent,
    val command: ForegroundServiceCommand,
    val source: ForegroundServiceCommandSource,
    val outcome: ForegroundServiceAuditOutcome,
) {
    init {
        ForegroundServiceAuditRecord(
            occurredAtEpochMillis = occurredAtEpochMillis,
            event = event,
            command = command,
            source = source,
            outcome = outcome,
        )
    }
}

class FileBackedForegroundServiceAuditLog(
    private val file: File,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val replaceFile: (File, String) -> Unit = ::replaceFileAtomically,
) {
    private val lock = Any()

    init {
        require(maxRecords > 0) { "Foreground service audit max records must be positive" }
    }

    fun record(record: ForegroundServiceAuditRecord) {
        val persisted =
            PersistedForegroundServiceAuditRecord(
                occurredAtEpochMillis = record.occurredAtEpochMillis,
                event = record.event,
                command = record.command,
                source = record.source,
                outcome = record.outcome,
            )

        synchronized(lock) {
            file.parentFile?.mkdirs()
            val retainedLines =
                (readRecordsLocked().map(PersistedForegroundServiceAuditRecord::toLine) + persisted.toLine())
                    .takeLast(maxRecords)
            replaceFile(file, retainedLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun readAll(): List<PersistedForegroundServiceAuditRecord> = synchronized(lock) {
        readRecordsLocked()
    }

    private fun readRecordsLocked(): List<PersistedForegroundServiceAuditRecord> = readLinesLocked().mapNotNull(::parseLineOrNull)

    private fun readLinesLocked(): List<String> {
        if (!file.exists()) {
            return emptyList()
        }

        return file.readLines().filter(String::isNotBlank)
    }

    companion object {
        const val DEFAULT_MAX_RECORDS: Int = 1_000
    }
}

object CellularProxyForegroundServiceAuditStore {
    fun foregroundServiceAuditLog(context: Context): FileBackedForegroundServiceAuditLog = FileBackedForegroundServiceAuditLog(
        file = File(context.applicationContext.filesDir, "audit/foreground-service.audit"),
    )
}

private fun PersistedForegroundServiceAuditRecord.toLine(): String = listOf(
    AUDIT_FORMAT_VERSION,
    occurredAtEpochMillis.toString(),
    event.name,
    command.name,
    source.name,
    outcome.name,
).joinToString(separator = "\t")

private fun parseLineOrNull(line: String): PersistedForegroundServiceAuditRecord? = try {
    parseLine(line)
} catch (_: Exception) {
    null
}

private fun parseLine(line: String): PersistedForegroundServiceAuditRecord {
    val fields = line.split('\t')
    require(fields.size == AUDIT_FIELD_COUNT) { "Malformed foreground service audit record" }
    require(fields[0] == AUDIT_FORMAT_VERSION) { "Unsupported foreground service audit format version" }

    return PersistedForegroundServiceAuditRecord(
        occurredAtEpochMillis = fields[1].toLong(),
        event = ForegroundServiceAuditEvent.valueOf(fields[2]),
        command = ForegroundServiceCommand.valueOf(fields[3]),
        source = ForegroundServiceCommandSource.valueOf(fields[4]),
        outcome = ForegroundServiceAuditOutcome.valueOf(fields[5]),
    )
}

private fun replaceFileAtomically(
    destination: File,
    contents: String,
) {
    val parent = destination.parentFile
    if (parent != null) {
        parent.mkdirs()
    }
    val tempFile = File.createTempFile("${destination.name}.", ".tmp", parent)
    try {
        tempFile.writeText(contents)
        try {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: UnsupportedOperationException) {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

private const val AUDIT_FORMAT_VERSION = "v1"
private const val AUDIT_FIELD_COUNT = 6
