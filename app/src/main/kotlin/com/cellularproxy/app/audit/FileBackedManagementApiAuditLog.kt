package com.cellularproxy.app.audit

import android.content.Context
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class ManagementApiAuditOutcome {
    Responded,
    RouteRejected,
    HandlerFailed,
    AuthorizationRejected,
}

data class ManagementApiAuditRecord(
    val operation: ManagementApiOperation?,
    val outcome: ManagementApiAuditOutcome,
    val statusCode: Int?,
    val disposition: ManagementApiStreamExchangeDisposition?,
) {
    init {
        statusCode?.let {
            require(it in HTTP_STATUS_CODE_RANGE) { "Management API audit status code must be in 100..599" }
        }
        when (outcome) {
            ManagementApiAuditOutcome.Responded -> {
                require(operation != null) { "Responded management API audit records require an operation" }
                require(statusCode != null) { "Responded management API audit records require a status code" }
                require(disposition == ManagementApiStreamExchangeDisposition.Routed) {
                    "Responded management API audit records require a routed disposition"
                }
            }
            ManagementApiAuditOutcome.RouteRejected -> {
                require(statusCode != null) { "Route-rejected management API audit records require a status code" }
                require(disposition == ManagementApiStreamExchangeDisposition.RouteRejected) {
                    "Route-rejected management API audit records require a route-rejected disposition"
                }
            }
            ManagementApiAuditOutcome.HandlerFailed -> {
                require(operation != null) { "Handler-failed management API audit records require an operation" }
                require(statusCode == null) { "Handler-failed management API audit records cannot have a status code" }
                require(disposition == null) { "Handler-failed management API audit records cannot have a disposition" }
            }
            ManagementApiAuditOutcome.AuthorizationRejected -> {
                require(operation == null) { "Authorization-rejected management API audit records cannot have an operation" }
                require(statusCode != null) { "Authorization-rejected management API audit records require a status code" }
                require(disposition == null) { "Authorization-rejected management API audit records cannot have a disposition" }
            }
        }
    }
}

data class PersistedManagementApiAuditRecord(
    val occurredAtEpochMillis: Long,
    val operation: ManagementApiOperation?,
    val outcome: ManagementApiAuditOutcome,
    val statusCode: Int?,
    val disposition: ManagementApiStreamExchangeDisposition?,
) {
    init {
        require(occurredAtEpochMillis >= 0) {
            "Management API audit timestamp must be non-negative"
        }
        ManagementApiAuditRecord(
            operation = operation,
            outcome = outcome,
            statusCode = statusCode,
            disposition = disposition,
        )
    }
}

class FileBackedManagementApiAuditLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val replaceFile: (File, String) -> Unit = ::replaceFileAtomically,
) {
    private val lock = Any()

    init {
        require(maxRecords > 0) { "Management API audit max records must be positive" }
    }

    fun record(record: ManagementApiAuditRecord) {
        val persisted =
            PersistedManagementApiAuditRecord(
                occurredAtEpochMillis = clock(),
                operation = record.operation,
                outcome = record.outcome,
                statusCode = record.statusCode,
                disposition = record.disposition,
            )

        synchronized(lock) {
            file.parentFile?.mkdirs()
            val retainedLines =
                (readRecordsLocked().map(PersistedManagementApiAuditRecord::toLine) + persisted.toLine())
                    .takeLast(maxRecords)
            replaceFile(file, retainedLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun readAll(): List<PersistedManagementApiAuditRecord> = synchronized(lock) {
        readRecordsLocked()
    }

    private fun readRecordsLocked(): List<PersistedManagementApiAuditRecord> = readLinesLocked().mapNotNull(::parseLineOrNull)

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

object CellularProxyManagementAuditStore {
    fun managementApiAuditLog(context: Context): FileBackedManagementApiAuditLog = FileBackedManagementApiAuditLog(
        file = File(context.applicationContext.filesDir, "audit/management-api.audit"),
    )
}

private fun PersistedManagementApiAuditRecord.toLine(): String = listOf(
    AUDIT_FORMAT_VERSION,
    occurredAtEpochMillis.toString(),
    operation?.name ?: NULL_FIELD,
    outcome.name,
    statusCode?.toString() ?: NULL_FIELD,
    disposition?.name ?: NULL_FIELD,
).joinToString(separator = "\t")

private fun parseLineOrNull(line: String): PersistedManagementApiAuditRecord? = try {
    parseLine(line)
} catch (_: Exception) {
    null
}

private fun parseLine(line: String): PersistedManagementApiAuditRecord {
    val fields = line.split('\t')
    require(fields.size == AUDIT_FIELD_COUNT) { "Malformed management API audit record" }
    require(fields[0] == AUDIT_FORMAT_VERSION) { "Unsupported management API audit format version" }

    return PersistedManagementApiAuditRecord(
        occurredAtEpochMillis = fields[1].toLong(),
        operation = fields[2].takeUnless { it == NULL_FIELD }?.let(ManagementApiOperation::valueOf),
        outcome = ManagementApiAuditOutcome.valueOf(fields[3]),
        statusCode = fields[4].takeUnless { it == NULL_FIELD }?.toInt(),
        disposition =
            fields[5]
                .takeUnless { it == NULL_FIELD }
                ?.let(ManagementApiStreamExchangeDisposition::valueOf),
    )
}

private fun replaceFileAtomically(
    destination: File,
    contents: String,
) {
    replaceManagementAuditFileAtomically(
        destination = destination,
        contents = contents,
    )
}

internal fun replaceManagementAuditFileAtomically(
    destination: File,
    contents: String,
    moveAtomically: (Path, Path) -> Unit = ::moveManagementAuditFileAtomically,
    moveNonAtomically: (Path, Path) -> Unit = ::moveManagementAuditFileNonAtomically,
) {
    val parent = destination.parentFile
    if (parent != null) {
        parent.mkdirs()
    }
    val tempFile = File.createTempFile("${destination.name}.", ".tmp", parent)
    try {
        tempFile.writeText(contents)
        try {
            moveAtomically(tempFile.toPath(), destination.toPath())
        } catch (_: UnsupportedOperationException) {
            moveNonAtomically(tempFile.toPath(), destination.toPath())
        } catch (_: AtomicMoveNotSupportedException) {
            moveNonAtomically(tempFile.toPath(), destination.toPath())
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

private fun moveManagementAuditFileAtomically(
    source: Path,
    target: Path,
) {
    Files.move(
        source,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun moveManagementAuditFileNonAtomically(
    source: Path,
    target: Path,
) {
    Files.move(
        source,
        target,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private val HTTP_STATUS_CODE_RANGE = 100..599
private const val AUDIT_FORMAT_VERSION = "v1"
private const val AUDIT_FIELD_COUNT = 6
private const val NULL_FIELD = "-"
