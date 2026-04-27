package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.util.concurrent.CancellationException

class RootAvailabilityChecker(
    private val executor: RootCommandExecutor,
) : RotationRootAvailabilityProbe {
    override fun check(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets,
    ): RootAvailabilityCheckResult = checkInternal(timeoutMillis = timeoutMillis, secrets = secrets)

    fun check(timeoutMillis: Long): RootAvailabilityCheckResult = check(timeoutMillis = timeoutMillis, secrets = LogRedactionSecrets())

    private fun checkInternal(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets,
    ): RootAvailabilityCheckResult {
        require(timeoutMillis > 0) { "Root availability timeout must be positive" }

        val execution =
            try {
                executor.execute(
                    command = RootShellCommands.rootAvailabilityCheck(),
                    timeoutMillis = timeoutMillis,
                    secrets = secrets,
                )
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return RootAvailabilityCheckResult(
                    status = RootAvailabilityStatus.Unavailable,
                    execution = null,
                    failureReason = RootAvailabilityCheckFailure.ProcessExecutionFailed,
                )
            }

        return when (execution.result.outcome) {
            RootCommandOutcome.Success -> {
                if (execution.rawStdout.trim() == ROOT_UID) {
                    RootAvailabilityCheckResult(
                        status = RootAvailabilityStatus.Available,
                        execution = execution,
                    )
                } else {
                    RootAvailabilityCheckResult(
                        status = RootAvailabilityStatus.Unavailable,
                        execution = execution,
                        failureReason = RootAvailabilityCheckFailure.InvalidUidOutput,
                    )
                }
            }
            RootCommandOutcome.Failure ->
                RootAvailabilityCheckResult(
                    status = RootAvailabilityStatus.Unavailable,
                    execution = execution,
                    failureReason = RootAvailabilityCheckFailure.CommandFailed,
                )
            RootCommandOutcome.Timeout ->
                RootAvailabilityCheckResult(
                    status = RootAvailabilityStatus.Unavailable,
                    execution = execution,
                    failureReason = RootAvailabilityCheckFailure.CommandTimedOut,
                )
        }
    }
}

data class RootAvailabilityCheckResult(
    val status: RootAvailabilityStatus,
    val execution: RootCommandExecution? = null,
    val failureReason: RootAvailabilityCheckFailure? = null,
) {
    init {
        when (status) {
            RootAvailabilityStatus.Unknown -> {
                require(execution == null) { "Unknown root availability must not have an execution" }
                require(failureReason == null) { "Unknown root availability must not have a failure reason" }
            }
            RootAvailabilityStatus.Available -> {
                require(execution != null) { "Available root status requires a successful execution" }
                require(failureReason == null) { "Available root status must not have a failure reason" }
                require(execution.result.category == RootCommandCategory.RootAvailabilityCheck) {
                    "Root availability execution must use the availability-check category"
                }
                require(execution.result.outcome == RootCommandOutcome.Success) {
                    "Available root status requires a successful root command"
                }
                require(execution.rawStdout.trim() == ROOT_UID) {
                    "Available root status requires uid 0 output"
                }
            }
            RootAvailabilityStatus.Unavailable -> {
                require(failureReason != null) { "Unavailable root status requires a failure reason" }
                require(execution?.result?.category != null || failureReason == RootAvailabilityCheckFailure.ProcessExecutionFailed) {
                    "Unavailable root status without execution must be a process execution failure"
                }
                require(execution == null || execution.result.category == RootCommandCategory.RootAvailabilityCheck) {
                    "Root availability execution must use the availability-check category"
                }
                failureReason.requireMatchingExecution(execution)
            }
        }
    }
}

enum class RootAvailabilityCheckFailure {
    CommandFailed,
    CommandTimedOut,
    InvalidUidOutput,
    ProcessExecutionFailed,
}

private fun RootAvailabilityCheckFailure.requireMatchingExecution(execution: RootCommandExecution?) {
    when (this) {
        RootAvailabilityCheckFailure.CommandFailed -> {
            require(execution != null) { "Command-failed root availability requires an execution" }
            require(execution.result.category == RootCommandCategory.RootAvailabilityCheck) {
                "Root availability execution must use the availability-check category"
            }
            require(execution.result.outcome == RootCommandOutcome.Failure) {
                "Command-failed root availability requires a failed command result"
            }
        }
        RootAvailabilityCheckFailure.CommandTimedOut -> {
            require(execution != null) { "Timed-out root availability requires an execution" }
            require(execution.result.category == RootCommandCategory.RootAvailabilityCheck) {
                "Root availability execution must use the availability-check category"
            }
            require(execution.result.outcome == RootCommandOutcome.Timeout) {
                "Timed-out root availability requires a timed-out command result"
            }
        }
        RootAvailabilityCheckFailure.InvalidUidOutput -> {
            require(execution != null) { "Invalid uid root availability requires an execution" }
            require(execution.result.category == RootCommandCategory.RootAvailabilityCheck) {
                "Root availability execution must use the availability-check category"
            }
            require(execution.result.outcome == RootCommandOutcome.Success) {
                "Invalid uid root availability requires a successful command result"
            }
            require(execution.rawStdout.trim() != ROOT_UID) {
                "Invalid uid root availability cannot contain uid 0 output"
            }
        }
        RootAvailabilityCheckFailure.ProcessExecutionFailed ->
            require(execution == null) { "Process execution failure must not have an execution" }
    }
}

private const val ROOT_UID = "0"
