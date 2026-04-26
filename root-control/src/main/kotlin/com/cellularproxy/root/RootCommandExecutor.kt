package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult

class RootShellCommand private constructor(
    val category: RootCommandCategory,
    argv: List<String>,
) {
    val argv: List<String> = argv.toList()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RootShellCommand &&
            category == other.category &&
            argv == other.argv

    override fun hashCode(): Int =
        31 * category.hashCode() + argv.hashCode()

    override fun toString(): String =
        "RootShellCommand(category=$category, argv=$argv)"

    companion object {
        internal fun trusted(
            category: RootCommandCategory,
            argv: List<String>,
        ): RootShellCommand {
            require(argv.isNotEmpty()) { "Root command argv must not be empty" }
            require(argv.none { it.isBlank() }) { "Root command argv entries must not be blank" }
            return RootShellCommand(category, argv)
        }
    }
}

fun interface RootCommandProcessExecutor {
    fun execute(
        command: RootShellCommand,
        timeoutMillis: Long,
    ): RootCommandProcessResult
}

sealed interface RootCommandProcessResult {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : RootCommandProcessResult

    data class TimedOut(
        val stdout: String,
        val stderr: String,
    ) : RootCommandProcessResult
}

class RootCommandExecution private constructor(
    val result: RootCommandResult,
    auditRecords: List<RootCommandAuditRecord>,
) {
    val auditRecords: List<RootCommandAuditRecord> = auditRecords.toList()

    companion object {
        internal fun completed(
            result: RootCommandResult,
            started: RootCommandAuditRecord,
            completed: RootCommandAuditRecord,
        ): RootCommandExecution {
            require(started.phase == RootCommandAuditPhase.Started) {
                "Started audit record must have started phase"
            }
            require(started.outcome == null) {
                "Started audit record must not have an outcome"
            }
            require(started.exitCode == null) {
                "Started audit record must not have an exit code"
            }
            require(started.stdout == null) {
                "Started audit record must not have stdout"
            }
            require(started.stderr == null) {
                "Started audit record must not have stderr"
            }
            require(completed.phase == RootCommandAuditPhase.Completed) {
                "Completed audit record must have completed phase"
            }
            require(started.category == result.category) {
                "Started audit record category must match root command result"
            }
            require(completed.category == result.category) {
                "Completed audit record category must match root command result"
            }
            require(completed.outcome == result.outcome) {
                "Completed audit record outcome must match root command result"
            }
            require(completed.exitCode == result.exitCode) {
                "Completed audit record exit code must match root command result"
            }
            require(completed.stdout == result.stdout) {
                "Completed audit record stdout must match root command result"
            }
            require(completed.stderr == result.stderr) {
                "Completed audit record stderr must match root command result"
            }
            return RootCommandExecution(
                result = result,
                auditRecords = listOf(started, completed),
            )
        }
    }
}

class RootCommandExecutor(
    private val processExecutor: RootCommandProcessExecutor,
    private val recordAudit: (RootCommandAuditRecord) -> Unit = {},
) {
    fun execute(
        command: RootShellCommand,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RootCommandExecution {
        require(timeoutMillis > 0) { "Root command timeout must be positive" }

        val started = RootCommandAuditRecord.started(command.category)
        recordAudit(started)
        val processResult = try {
            processExecutor.execute(
                command = command,
                timeoutMillis = timeoutMillis,
            )
        } catch (exception: Exception) {
            recordAudit(
                RootCommandAuditRecord.failedToStart(
                    category = command.category,
                    reason = "Root command process execution failed: ${exception::class.simpleName ?: "Exception"}",
                    secrets = secrets,
                ),
            )
            throw exception
        }
        val result = when (processResult) {
            is RootCommandProcessResult.Completed ->
                RootCommandResult.completed(
                    category = command.category,
                    exitCode = processResult.exitCode,
                    stdout = processResult.stdout,
                    stderr = processResult.stderr,
                    secrets = secrets,
                )
            is RootCommandProcessResult.TimedOut ->
                RootCommandResult.timedOut(
                    category = command.category,
                    stdout = processResult.stdout,
                    stderr = processResult.stderr,
                    secrets = secrets,
                )
        }
        val completed = RootCommandAuditRecord.completed(result)
        recordAudit(completed)

        return RootCommandExecution.completed(
            result = result,
            started = started,
            completed = completed,
        )
    }
}

object RootShellCommands {
    fun rootAvailabilityCheck(): RootShellCommand =
        suCommand(
            category = RootCommandCategory.RootAvailabilityCheck,
            shellCommand = "id -u",
        )

    fun mobileDataDisable(): RootShellCommand =
        suCommand(
            category = RootCommandCategory.MobileDataDisable,
            shellCommand = "svc data disable",
        )

    fun mobileDataEnable(): RootShellCommand =
        suCommand(
            category = RootCommandCategory.MobileDataEnable,
            shellCommand = "svc data enable",
        )

    fun airplaneModeEnable(): RootShellCommand =
        suCommand(
            category = RootCommandCategory.AirplaneModeEnable,
            shellCommand = "cmd connectivity airplane-mode enable",
        )

    fun airplaneModeDisable(): RootShellCommand =
        suCommand(
            category = RootCommandCategory.AirplaneModeDisable,
            shellCommand = "cmd connectivity airplane-mode disable",
        )

    fun serviceRestart(packageName: String): RootShellCommand {
        val trustedPackageName = packageName.validatedAndroidPackageName()

        return suCommand(
            category = RootCommandCategory.ServiceRestart,
            shellCommand = "am force-stop $trustedPackageName && monkey -p $trustedPackageName 1",
        )
    }

    private fun suCommand(
        category: RootCommandCategory,
        shellCommand: String,
    ): RootShellCommand =
        RootShellCommand.trusted(
            category = category,
            argv = listOf("su", "-c", shellCommand),
        )
}

private fun String.validatedAndroidPackageName(): String {
    require(isNotBlank()) { "Package name must not be blank" }
    require(ANDROID_PACKAGE_NAME.matches(this)) { "Package name contains unsafe characters" }
    return this
}

private val ANDROID_PACKAGE_NAME =
    Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
