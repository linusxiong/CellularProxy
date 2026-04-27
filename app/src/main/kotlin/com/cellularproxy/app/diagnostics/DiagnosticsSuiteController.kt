package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.time.Duration.Companion.nanoseconds

fun interface DiagnosticCheck {
    fun run(): DiagnosticCheckResult
}

data class DiagnosticCheckResult(
    val status: DiagnosticResultStatus,
    val errorCategory: String? = null,
    val details: String? = null,
)

class DiagnosticsSuiteController(
    private val checks: Map<DiagnosticCheckType, DiagnosticCheck>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val completed = mutableListOf<DiagnosticRunRecord>()
    private val running = mutableSetOf<DiagnosticCheckType>()

    fun run(type: DiagnosticCheckType): DiagnosticRunRecord {
        synchronized(lock) {
            running += type
        }
        val startedAt = nanoTime()
        val check = checks[type]
        val result =
            try {
                check?.run()
                    ?: DiagnosticCheckResult(
                        status = DiagnosticResultStatus.Failed,
                        errorCategory = "missing-check",
                        details = "No diagnostic check registered for ${type.label}",
                    )
            } catch (exception: Exception) {
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = exception::class.simpleName ?: "Exception",
                    details = exception.message ?: exception.toString(),
                )
            }
        val duration = (nanoTime() - startedAt).nanoseconds
        val record =
            DiagnosticRunRecord(
                type = type,
                status = result.status,
                duration = duration,
                errorCategory = result.errorCategory,
                details = result.details,
            )
        synchronized(lock) {
            running -= type
            completed += record
        }
        return record
    }

    fun isRunning(type: DiagnosticCheckType): Boolean = synchronized(lock) {
        type in running
    }

    fun resultModel(secrets: LogRedactionSecrets = LogRedactionSecrets()): DiagnosticsResultModel = synchronized(lock) {
        DiagnosticsResultModel.from(
            completed = completed.toList(),
            running = running.toSet(),
            secrets = secrets,
        )
    }
}
