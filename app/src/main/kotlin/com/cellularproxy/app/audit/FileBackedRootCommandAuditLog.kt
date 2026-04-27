package com.cellularproxy.app.audit

import android.content.Context
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

data class PersistedRootCommandAuditRecord(
    val occurredAtEpochMillis: Long,
    val phase: RootCommandAuditPhase,
    val category: RootCommandCategory,
    val outcome: RootCommandOutcome?,
    val exitCode: Int?,
    val stdout: String?,
    val stderr: String?,
)

class FileBackedRootCommandAuditLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val replaceFile: (File, String) -> Unit = ::replaceFileAtomically,
) {
    private val lock = Any()

    init {
        require(maxRecords > 0) { "Root audit max records must be positive" }
    }

    fun record(record: RootCommandAuditRecord) {
        val persisted =
            PersistedRootCommandAuditRecord(
                occurredAtEpochMillis = clock(),
                phase = record.phase,
                category = record.category,
                outcome = record.outcome,
                exitCode = record.exitCode,
                stdout = record.stdout,
                stderr = record.stderr,
            )

        synchronized(lock) {
            file.parentFile?.mkdirs()
            val retainedLines =
                (readRecordsLocked().map(PersistedRootCommandAuditRecord::toLine) + persisted.toLine())
                    .takeLast(maxRecords)
            replaceFile(file, retainedLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun readAll(): List<PersistedRootCommandAuditRecord> =
        synchronized(lock) {
            readRecordsLocked()
        }

    private fun readRecordsLocked(): List<PersistedRootCommandAuditRecord> = readLinesLocked().mapNotNull(::parseLineOrNull)

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

object CellularProxyRootAuditStore {
    fun rootCommandAuditLog(context: Context): FileBackedRootCommandAuditLog =
        FileBackedRootCommandAuditLog(
            file = File(context.applicationContext.filesDir, "audit/root-commands.audit"),
        )
}

private fun PersistedRootCommandAuditRecord.toLine(): String =
    listOf(
        AUDIT_FORMAT_VERSION,
        occurredAtEpochMillis.toString(),
        phase.name,
        category.name,
        outcome?.name ?: NULL_FIELD,
        exitCode?.toString() ?: NULL_FIELD,
        stdout.encodeNullable(),
        stderr.encodeNullable(),
    ).joinToString(separator = "\t")

private fun parseLineOrNull(line: String): PersistedRootCommandAuditRecord? =
    try {
        parseLine(line)
    } catch (_: Exception) {
        null
    }

private fun parseLine(line: String): PersistedRootCommandAuditRecord {
    val fields = line.split('\t')
    require(fields.size == AUDIT_FIELD_COUNT) { "Malformed root audit record" }
    require(fields[0] == AUDIT_FORMAT_VERSION) { "Unsupported root audit format version" }

    return PersistedRootCommandAuditRecord(
        occurredAtEpochMillis = fields[1].toLong(),
        phase = RootCommandAuditPhase.valueOf(fields[2]),
        category = RootCommandCategory.valueOf(fields[3]),
        outcome = fields[4].takeUnless { it == NULL_FIELD }?.let(RootCommandOutcome::valueOf),
        exitCode = fields[5].takeUnless { it == NULL_FIELD }?.toInt(),
        stdout = fields[6].decodeNullable(),
        stderr = fields[7].decodeNullable(),
    )
}

private fun String?.encodeNullable(): String =
    if (this == null) {
        NULL_FIELD
    } else {
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(toByteArray(Charsets.UTF_8))
    }

private fun String.decodeNullable(): String? =
    if (this == NULL_FIELD) {
        null
    } else {
        Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)
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
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

private const val AUDIT_FORMAT_VERSION = "v1"
private const val AUDIT_FIELD_COUNT = 8
private const val NULL_FIELD = "-"
