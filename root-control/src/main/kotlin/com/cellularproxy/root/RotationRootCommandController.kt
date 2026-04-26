package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationOperation

class RotationRootCommandController(
    private val mobileDataController: MobileDataRootController,
    private val airplaneModeController: AirplaneModeRootController,
) {
    fun runDisableCommand(
        operation: RotationOperation,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RotationRootCommandResult {
        require(timeoutMillis > 0) { "Rotation root command timeout must be positive" }

        return when (operation) {
            RotationOperation.MobileData ->
                mobileDataController.disable(
                    timeoutMillis = timeoutMillis,
                    secrets = secrets,
                ).toRotationRootCommandResult(
                    operation = operation,
                    phase = RotationRootCommandPhase.DisableCommand,
                )
            RotationOperation.AirplaneMode ->
                airplaneModeController.enable(
                    timeoutMillis = timeoutMillis,
                    secrets = secrets,
                ).toRotationRootCommandResult(
                    operation = operation,
                    phase = RotationRootCommandPhase.DisableCommand,
                )
        }
    }

    fun runEnableCommand(
        operation: RotationOperation,
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RotationRootCommandResult {
        require(timeoutMillis > 0) { "Rotation root command timeout must be positive" }

        return when (operation) {
            RotationOperation.MobileData ->
                mobileDataController.enable(
                    timeoutMillis = timeoutMillis,
                    secrets = secrets,
                ).toRotationRootCommandResult(
                    operation = operation,
                    phase = RotationRootCommandPhase.EnableCommand,
                )
            RotationOperation.AirplaneMode ->
                airplaneModeController.disable(
                    timeoutMillis = timeoutMillis,
                    secrets = secrets,
                ).toRotationRootCommandResult(
                    operation = operation,
                    phase = RotationRootCommandPhase.EnableCommand,
                )
        }
    }
}

data class RotationRootCommandResult(
    val operation: RotationOperation,
    val phase: RotationRootCommandPhase,
    val execution: RootCommandExecution? = null,
    val failureReason: RotationRootCommandFailure? = null,
) {
    val succeeded: Boolean
        get() = failureReason == null

    init {
        if (succeeded) {
            require(execution != null) { "Successful rotation root command requires an execution" }
            require(execution.result.outcome == RootCommandOutcome.Success) {
                "Successful rotation root command requires a successful root command"
            }
        } else {
            require(failureReason != null) { "Failed rotation root command requires a failure reason" }
        }

        require(execution == null || execution.result.category == expectedCategory) {
            "Rotation root command execution category must match the operation and phase"
        }

        when (failureReason) {
            null -> Unit
            RotationRootCommandFailure.CommandFailed -> {
                require(execution != null) { "Command-failed rotation result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Failure) {
                    "Command-failed rotation result requires a failed root command"
                }
            }
            RotationRootCommandFailure.CommandTimedOut -> {
                require(execution != null) { "Timed-out rotation result requires an execution" }
                require(execution.result.outcome == RootCommandOutcome.Timeout) {
                    "Timed-out rotation result requires a timed-out root command"
                }
            }
            RotationRootCommandFailure.ProcessExecutionFailed ->
                require(execution == null) {
                    "Process execution failure must not have a rotation command execution"
                }
        }
    }

    fun toRotationEvent(): RotationEvent.RootCommandCompleted? =
        execution?.let { RotationEvent.RootCommandCompleted(it.result) }

    private val expectedCategory: RootCommandCategory
        get() = when (operation) {
            RotationOperation.MobileData -> when (phase) {
                RotationRootCommandPhase.DisableCommand -> RootCommandCategory.MobileDataDisable
                RotationRootCommandPhase.EnableCommand -> RootCommandCategory.MobileDataEnable
            }
            RotationOperation.AirplaneMode -> when (phase) {
                RotationRootCommandPhase.DisableCommand -> RootCommandCategory.AirplaneModeEnable
                RotationRootCommandPhase.EnableCommand -> RootCommandCategory.AirplaneModeDisable
            }
        }
}

enum class RotationRootCommandPhase {
    DisableCommand,
    EnableCommand,
}

enum class RotationRootCommandFailure {
    CommandFailed,
    CommandTimedOut,
    ProcessExecutionFailed,
}

private fun MobileDataRootCommandResult.toRotationRootCommandResult(
    operation: RotationOperation,
    phase: RotationRootCommandPhase,
): RotationRootCommandResult =
    RotationRootCommandResult(
        operation = operation,
        phase = phase,
        execution = execution,
        failureReason = when (failureReason) {
            null -> null
            MobileDataRootCommandFailure.CommandFailed -> RotationRootCommandFailure.CommandFailed
            MobileDataRootCommandFailure.CommandTimedOut -> RotationRootCommandFailure.CommandTimedOut
            MobileDataRootCommandFailure.ProcessExecutionFailed -> RotationRootCommandFailure.ProcessExecutionFailed
        },
    )

private fun AirplaneModeRootCommandResult.toRotationRootCommandResult(
    operation: RotationOperation,
    phase: RotationRootCommandPhase,
): RotationRootCommandResult =
    RotationRootCommandResult(
        operation = operation,
        phase = phase,
        execution = execution,
        failureReason = when (failureReason) {
            null -> null
            AirplaneModeRootCommandFailure.CommandFailed -> RotationRootCommandFailure.CommandFailed
            AirplaneModeRootCommandFailure.CommandTimedOut -> RotationRootCommandFailure.CommandTimedOut
            AirplaneModeRootCommandFailure.ProcessExecutionFailed -> RotationRootCommandFailure.ProcessExecutionFailed
        },
    )
