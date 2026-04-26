package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.util.concurrent.CancellationException

class ServiceRestartRootController(
    private val executor: RootCommandExecutor,
) {
    fun restart(
        packageName: String,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): ServiceRestartRootCommandResult {
        require(timeoutMillis > 0) { "Service restart root command timeout must be positive" }

        val command = RootShellCommands.serviceRestart(packageName)
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
            return ServiceRestartRootCommandResult(
                packageName = packageName,
                execution = null,
                failureReason = ServiceRestartRootCommandFailure.ProcessExecutionFailed,
            )
        }

        return ServiceRestartRootCommandResult(
            packageName = packageName,
            execution = execution,
            failureReason = when (execution.result.outcome) {
                RootCommandOutcome.Success -> null
                RootCommandOutcome.Failure -> ServiceRestartRootCommandFailure.CommandFailed
                RootCommandOutcome.Timeout -> ServiceRestartRootCommandFailure.CommandTimedOut
            },
        )
    }
}

data class ServiceRestartRootCommandResult(
    val packageName: String,
    val execution: RootCommandExecution? = null,
    val failureReason: ServiceRestartRootCommandFailure? = null,
) {
    val succeeded: Boolean
        get() = failureReason == null

    init {
        if (succeeded) {
            require(execution != null) { "Successful service restart command requires an execution" }
            require(execution.result.outcome == RootCommandOutcome.Success) {
                "Successful service restart command requires a successful root command"
            }
        } else {
            require(failureReason != null) { "Failed service restart command requires a failure reason" }
        }

        require(execution == null || execution.result.category == RootCommandCategory.ServiceRestart) {
            "Service restart command execution category must be ServiceRestart"
        }

        when (failureReason) {
            null -> Unit
            ServiceRestartRootCommandFailure.CommandFailed -> {
                require(execution != null) { "Command-failed service restart result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Failure) {
                    "Command-failed service restart result requires a failed root command"
                }
            }
            ServiceRestartRootCommandFailure.CommandTimedOut -> {
                require(execution != null) { "Timed-out service restart result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Timeout) {
                    "Timed-out service restart result requires a timed-out root command"
                }
            }
            ServiceRestartRootCommandFailure.ProcessExecutionFailed ->
                require(execution == null) {
                    "Process execution failure must not have a service restart execution"
                }
        }
    }
}

enum class ServiceRestartRootCommandFailure {
    CommandFailed,
    CommandTimedOut,
    ProcessExecutionFailed,
}
