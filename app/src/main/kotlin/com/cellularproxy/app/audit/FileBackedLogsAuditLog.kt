package com.cellularproxy.app.audit

import android.content.Context
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

enum class LogsAuditRecordCategory {
    AppRuntime,
    ProxyServer,
    CloudflareTunnel,
    Rotation,
    Audit,
    ManagementApi,
    RootCommands,
}

enum class LogsAuditRecordSeverity {
    Info,
    Warning,
    Failed,
}

data class PersistedLogsAuditRecord(
    val occurredAtEpochMillis: Long,
    val category: LogsAuditRecordCategory,
    val severity: LogsAuditRecordSeverity,
    val title: String,
    val detail: String,
) {
    init {
        require(occurredAtEpochMillis >= 0) { "Log record timestamp must be non-negative." }
        require(title.isNotBlank()) { "Log record title must not be blank." }
    }
}

class FileBackedLogsAuditLog(
    private val file: File,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    private val replaceFile: (File, String) -> Unit = ::replaceFileAtomically,
) {
    private val lock = Any()

    init {
        require(maxRecords > 0) { "Logs audit max records must be positive" }
    }

    fun record(record: PersistedLogsAuditRecord) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val safeRecord = record.redacted(redactionSecretsProvider())
            val retainedLines =
                (readRecordsLocked().map(PersistedLogsAuditRecord::toLine) + safeRecord.toLine())
                    .takeLast(maxRecords)
            replaceFile(file, retainedLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun readAll(): List<PersistedLogsAuditRecord> = synchronized(lock) {
        readRecordsLocked()
    }

    private fun readRecordsLocked(): List<PersistedLogsAuditRecord> = readLinesLocked().mapNotNull(::parseLineOrNull)

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

object CellularProxyLogsAuditStore {
    fun logsAuditLog(
        context: Context,
        redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    ): FileBackedLogsAuditLog = FileBackedLogsAuditLog(
        file = File(context.applicationContext.filesDir, "audit/logs.audit"),
        redactionSecretsProvider = redactionSecretsProvider,
    )
}

private fun PersistedLogsAuditRecord.toLine(): String = listOf(
    LOG_FORMAT_VERSION,
    occurredAtEpochMillis.toString(),
    category.name,
    severity.name,
    title.encodeLogField(),
    detail.encodeLogField(),
).joinToString(separator = "\t")

private fun PersistedLogsAuditRecord.redacted(secrets: LogRedactionSecrets): PersistedLogsAuditRecord = copy(
    title = LogRedactor.redact(title, secrets),
    detail = LogRedactor.redact(detail, secrets),
)

private fun parseLineOrNull(line: String): PersistedLogsAuditRecord? = try {
    parseLine(line)
} catch (_: Exception) {
    null
}

private fun parseLine(line: String): PersistedLogsAuditRecord {
    val fields = line.split('\t')
    require(fields.size == LOG_FIELD_COUNT) { "Malformed logs audit record" }
    require(fields[0] == LOG_FORMAT_VERSION) { "Unsupported logs audit format version" }

    return PersistedLogsAuditRecord(
        occurredAtEpochMillis = fields[1].toLong(),
        category = LogsAuditRecordCategory.valueOf(fields[2]),
        severity = LogsAuditRecordSeverity.valueOf(fields[3]),
        title = fields[4].decodeLogField(),
        detail = fields[5].decodeLogField(),
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

private fun String.encodeLogField(): String = Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.decodeLogField(): String = String(Base64.getDecoder().decode(this), Charsets.UTF_8)

private const val LOG_FORMAT_VERSION = "v1"
private const val LOG_FIELD_COUNT = 6
