package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.util.concurrent.CancellationException

class MobileDataRootController(
    private val executor: RootCommandExecutor,
) {
    fun disable(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): MobileDataRootCommandResult = run(
        action = MobileDataRootAction.Disable,
        command = RootShellCommands.mobileDataDisable(),
        timeoutMillis = timeoutMillis,
        secrets = secrets,
    )

    fun enable(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): MobileDataRootCommandResult = run(
        action = MobileDataRootAction.Enable,
        command = RootShellCommands.mobileDataEnable(),
        timeoutMillis = timeoutMillis,
        secrets = secrets,
    )

    private fun run(
        action: MobileDataRootAction,
        command: RootShellCommand,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets,
    ): MobileDataRootCommandResult {
        require(timeoutMillis > 0) { "Mobile data root command timeout must be positive" }

        val execution =
            try {
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
                return MobileDataRootCommandResult(
                    action = action,
                    execution = null,
                    failureReason = MobileDataRootCommandFailure.ProcessExecutionFailed,
                )
            }

        return MobileDataRootCommandResult(
            action = action,
            execution = execution,
            failureReason =
                when (execution.result.outcome) {
                    RootCommandOutcome.Success -> null
                    RootCommandOutcome.Failure -> MobileDataRootCommandFailure.CommandFailed
                    RootCommandOutcome.Timeout -> MobileDataRootCommandFailure.CommandTimedOut
                },
        )
    }
}

data class MobileDataRootCommandResult(
    val action: MobileDataRootAction,
    val execution: RootCommandExecution? = null,
    val failureReason: MobileDataRootCommandFailure? = null,
) {
    val succeeded: Boolean
        get() = failureReason == null

    init {
        if (succeeded) {
            require(execution != null) { "Successful mobile data command requires an execution" }
            require(execution.result.outcome == RootCommandOutcome.Success) {
                "Successful mobile data command requires a successful root command"
            }
        } else {
            require(failureReason != null) { "Failed mobile data command requires a failure reason" }
        }

        require(execution == null || execution.result.category == action.expectedCategory) {
            "Mobile data command execution category must match the requested action"
        }

        when (failureReason) {
            null -> Unit
            MobileDataRootCommandFailure.CommandFailed -> {
                require(execution != null) { "Command-failed mobile data result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Failure) {
                    "Command-failed mobile data result requires a failed root command"
                }
            }
            MobileDataRootCommandFailure.CommandTimedOut -> {
                require(execution != null) { "Timed-out mobile data result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Timeout) {
                    "Timed-out mobile data result requires a timed-out root command"
                }
            }
            MobileDataRootCommandFailure.ProcessExecutionFailed ->
                require(execution == null) {
                    "Process execution failure must not have a mobile data execution"
                }
        }
    }
}

enum class MobileDataRootAction {
    Disable,
    Enable,
}

enum class MobileDataRootCommandFailure {
    CommandFailed,
    CommandTimedOut,
    ProcessExecutionFailed,
}

private val MobileDataRootAction.expectedCategory: RootCommandCategory
    get() =
        when (this) {
            MobileDataRootAction.Disable -> RootCommandCategory.MobileDataDisable
            MobileDataRootAction.Enable -> RootCommandCategory.MobileDataEnable
        }
