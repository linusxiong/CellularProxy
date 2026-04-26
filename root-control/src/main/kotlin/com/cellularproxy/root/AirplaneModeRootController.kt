package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.util.concurrent.CancellationException

class AirplaneModeRootController(
    private val executor: RootCommandExecutor,
) {
    fun enable(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): AirplaneModeRootCommandResult =
        run(
            action = AirplaneModeRootAction.Enable,
            command = RootShellCommands.airplaneModeEnable(),
            timeoutMillis = timeoutMillis,
            secrets = secrets,
        )

    fun disable(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): AirplaneModeRootCommandResult =
        run(
            action = AirplaneModeRootAction.Disable,
            command = RootShellCommands.airplaneModeDisable(),
            timeoutMillis = timeoutMillis,
            secrets = secrets,
        )

    private fun run(
        action: AirplaneModeRootAction,
        command: RootShellCommand,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets,
    ): AirplaneModeRootCommandResult {
        require(timeoutMillis > 0) { "Airplane mode root command timeout must be positive" }

        val execution = try {
            executor.execute(
                command = command,
                timeoutMillis = timeoutMillis,
                secrets = secrets,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return AirplaneModeRootCommandResult(
                action = action,
                execution = null,
                failureReason = AirplaneModeRootCommandFailure.ProcessExecutionFailed,
            )
        }

        return AirplaneModeRootCommandResult(
            action = action,
            execution = execution,
            failureReason = when (execution.result.outcome) {
                RootCommandOutcome.Success -> null
                RootCommandOutcome.Failure -> AirplaneModeRootCommandFailure.CommandFailed
                RootCommandOutcome.Timeout -> AirplaneModeRootCommandFailure.CommandTimedOut
            },
        )
    }
}

data class AirplaneModeRootCommandResult(
    val action: AirplaneModeRootAction,
    val execution: RootCommandExecution? = null,
    val failureReason: AirplaneModeRootCommandFailure? = null,
) {
    val succeeded: Boolean
        get() = failureReason == null

    init {
        if (succeeded) {
            require(execution != null) { "Successful airplane mode command requires an execution" }
            require(execution.result.outcome == RootCommandOutcome.Success) {
                "Successful airplane mode command requires a successful root command"
            }
        } else {
            require(failureReason != null) { "Failed airplane mode command requires a failure reason" }
        }

        require(execution == null || execution.result.category == action.expectedCategory) {
            "Airplane mode command execution category must match the requested action"
        }

        when (failureReason) {
            null -> Unit
            AirplaneModeRootCommandFailure.CommandFailed -> {
                require(execution != null) { "Command-failed airplane mode result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Failure) {
                    "Command-failed airplane mode result requires a failed root command"
                }
            }
            AirplaneModeRootCommandFailure.CommandTimedOut -> {
                require(execution != null) { "Timed-out airplane mode result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Timeout) {
                    "Timed-out airplane mode result requires a timed-out root command"
                }
            }
            AirplaneModeRootCommandFailure.ProcessExecutionFailed ->
                require(execution == null) {
                    "Process execution failure must not have an airplane mode execution"
                }
        }
    }
}

enum class AirplaneModeRootAction {
    Enable,
    Disable,
}

enum class AirplaneModeRootCommandFailure {
    CommandFailed,
    CommandTimedOut,
    ProcessExecutionFailed,
}

private val AirplaneModeRootAction.expectedCategory: RootCommandCategory
    get() = when (this) {
        AirplaneModeRootAction.Enable -> RootCommandCategory.AirplaneModeEnable
        AirplaneModeRootAction.Disable -> RootCommandCategory.AirplaneModeDisable
    }
