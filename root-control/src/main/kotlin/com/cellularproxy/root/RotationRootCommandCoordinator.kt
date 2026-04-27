package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState

class RotationRootCommandCoordinator(
    private val commandController: RotationRootCommandController,
    private val controlPlane: RotationControlPlane,
) {
    @Synchronized
    fun runCurrentCommand(
        timeoutMillis: Long,
        nowElapsedMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RotationRootCommandAdvanceResult {
        require(timeoutMillis > 0) { "Rotation root command timeout must be positive" }
        require(nowElapsedMillis >= 0) { "Rotation root command observation elapsed millis must not be negative" }

        val commandRequest =
            synchronized(controlPlane) {
                val snapshot = controlPlane.snapshot()
                val status = snapshot.status
                when (status.state) {
                    RotationState.RunningDisableCommand ->
                        RootCommandRequest(
                            phase = RotationRootCommandPhase.DisableCommand,
                            operation = requireNotNull(status.operation),
                            expectedSnapshot = snapshot,
                        )
                    RotationState.RunningEnableCommand ->
                        RootCommandRequest(
                            phase = RotationRootCommandPhase.EnableCommand,
                            operation = requireNotNull(status.operation),
                            expectedSnapshot = snapshot,
                        )
                    else -> return RotationRootCommandAdvanceResult.NoAction(snapshot)
                }
            }

        val commandResult =
            when (commandRequest.phase) {
                RotationRootCommandPhase.DisableCommand ->
                    commandController.runDisableCommand(
                        operation = commandRequest.operation,
                        timeoutMillis = timeoutMillis,
                        secrets = secrets,
                    )
                RotationRootCommandPhase.EnableCommand ->
                    commandController.runEnableCommand(
                        operation = commandRequest.operation,
                        timeoutMillis = timeoutMillis,
                        secrets = secrets,
                    )
            }

        val event = commandResult.toRotationEvent()

        synchronized(controlPlane) {
            val actualSnapshot = controlPlane.snapshot()
            if (actualSnapshot != commandRequest.expectedSnapshot) {
                return RotationRootCommandAdvanceResult.Stale(
                    commandResult = commandResult,
                    snapshot = actualSnapshot,
                )
            }

            return RotationRootCommandAdvanceResult.Applied(
                commandResult = commandResult,
                progress =
                    controlPlane.applyProgress(
                        event = event,
                        nowElapsedMillis = nowElapsedMillis,
                    ),
                snapshot = controlPlane.snapshot(),
            )
        }
    }

    private data class RootCommandRequest(
        val phase: RotationRootCommandPhase,
        val operation: RotationOperation,
        val expectedSnapshot: RotationControlPlaneSnapshot,
    ) {
        val expectedState: RotationState
            get() =
                when (phase) {
                    RotationRootCommandPhase.DisableCommand -> RotationState.RunningDisableCommand
                    RotationRootCommandPhase.EnableCommand -> RotationState.RunningEnableCommand
                }
    }
}

sealed interface RotationRootCommandAdvanceResult {
    data class Applied(
        val commandResult: RotationRootCommandResult,
        val progress: RotationProgressGateResult,
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationRootCommandAdvanceResult {
        init {
            require(progress.transition.accepted) {
                "Applied rotation root command results require an accepted transition"
            }
            require(snapshot.status == progress.transition.status) {
                "Applied rotation root command snapshot must match transition status"
            }
        }
    }

    data class Stale(
        val commandResult: RotationRootCommandResult,
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationRootCommandAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationRootCommandAdvanceResult
}
