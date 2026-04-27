package com.cellularproxy.shared.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor

class RootCommandResult private constructor(
    val category: RootCommandCategory,
    val outcome: RootCommandOutcome,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
) {
    val timedOut: Boolean
        get() = outcome == RootCommandOutcome.Timeout

    companion object {
        fun completed(
            category: RootCommandCategory,
            exitCode: Int,
            stdout: String,
            stderr: String,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): RootCommandResult =
            RootCommandResult(
                category = category,
                outcome = if (exitCode == 0) RootCommandOutcome.Success else RootCommandOutcome.Failure,
                exitCode = exitCode,
                stdout = LogRedactor.redact(stdout, secrets),
                stderr = LogRedactor.redact(stderr, secrets),
            )

        fun timedOut(
            category: RootCommandCategory,
            stdout: String,
            stderr: String,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): RootCommandResult =
            RootCommandResult(
                category = category,
                outcome = RootCommandOutcome.Timeout,
                exitCode = null,
                stdout = LogRedactor.redact(stdout, secrets),
                stderr = LogRedactor.redact(stderr, secrets),
            )
    }
}

enum class RootCommandCategory {
    RootAvailabilityCheck,
    MobileDataDisable,
    MobileDataEnable,
    AirplaneModeEnable,
    AirplaneModeDisable,
    ServiceRestart,
}

enum class RootCommandOutcome {
    Success,
    Failure,
    Timeout,
}

class RootCommandAuditRecord private constructor(
    val phase: RootCommandAuditPhase,
    val category: RootCommandCategory,
    val outcome: RootCommandOutcome?,
    val exitCode: Int?,
    val stdout: String?,
    val stderr: String?,
) {
    companion object {
        fun started(category: RootCommandCategory): RootCommandAuditRecord =
            RootCommandAuditRecord(
                phase = RootCommandAuditPhase.Started,
                category = category,
                outcome = null,
                exitCode = null,
                stdout = null,
                stderr = null,
            )

        fun completed(result: RootCommandResult): RootCommandAuditRecord =
            RootCommandAuditRecord(
                phase = RootCommandAuditPhase.Completed,
                category = result.category,
                outcome = result.outcome,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
            )

        fun failedToStart(
            category: RootCommandCategory,
            reason: String,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): RootCommandAuditRecord {
            require(reason.isNotBlank()) { "Root command start failure reason must not be blank" }
            return RootCommandAuditRecord(
                phase = RootCommandAuditPhase.Completed,
                category = category,
                outcome = RootCommandOutcome.Failure,
                exitCode = null,
                stdout = "",
                stderr = LogRedactor.redact(reason, secrets),
            )
        }
    }
}

enum class RootCommandAuditPhase {
    Started,
    Completed,
}
