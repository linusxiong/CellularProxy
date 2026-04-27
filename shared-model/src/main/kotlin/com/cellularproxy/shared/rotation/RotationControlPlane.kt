package com.cellularproxy.shared.rotation

import kotlin.time.Duration

class RotationControlPlane(
    initialStatus: RotationStatus = RotationStatus.idle(),
    initialLastTerminalElapsedMillis: Long? = null,
) {
    init {
        RotationControlPlaneSnapshot(
            status = initialStatus,
            lastTerminalElapsedMillis = initialLastTerminalElapsedMillis,
        )
    }

    private val sessionController = RotationSessionController(initialStatus = initialStatus)
    private val terminalTimestampTracker =
        TerminalRotationTimestampTracker(
            initialLastTerminalElapsedMillis = initialLastTerminalElapsedMillis,
        )
    private val startGate =
        RotationStartGate(
            sessionController = sessionController,
            terminalTimestampTracker = terminalTimestampTracker,
        )
    private val progressGate =
        RotationProgressGate(
            sessionController = sessionController,
            terminalTimestampTracker = terminalTimestampTracker,
        )
    private var transitionGeneration: Long = 0

    @get:Synchronized
    val currentStatus: RotationStatus
        get() = sessionController.currentStatus

    @get:Synchronized
    val lastTerminalElapsedMillis: Long?
        get() = terminalTimestampTracker.lastTerminalElapsedMillis

    @Synchronized
    fun requestStart(
        operation: RotationOperation,
        nowElapsedMillis: Long,
        cooldown: Duration,
    ): RotationStartGateResult {
        val result =
            startGate.requestStart(
                operation = operation,
                nowElapsedMillis = nowElapsedMillis,
                cooldown = cooldown,
            )
        if (result.startTransition.accepted) {
            transitionGeneration += 1
        }
        return result
    }

    @Synchronized
    fun applyProgress(
        event: RotationEvent,
        nowElapsedMillis: Long,
    ): RotationProgressGateResult {
        val result =
            progressGate.apply(
                event = event,
                nowElapsedMillis = nowElapsedMillis,
            )
        if (result.transition.accepted) {
            transitionGeneration += 1
        }
        return result
    }

    @Synchronized
    fun snapshot(): RotationControlPlaneSnapshot =
        RotationControlPlaneSnapshot(
            status = sessionController.currentStatus,
            lastTerminalElapsedMillis = terminalTimestampTracker.lastTerminalElapsedMillis,
            transitionGeneration = transitionGeneration,
        )
}

data class RotationControlPlaneSnapshot(
    val status: RotationStatus,
    val lastTerminalElapsedMillis: Long?,
    val transitionGeneration: Long = 0,
) {
    init {
        require(lastTerminalElapsedMillis == null || lastTerminalElapsedMillis >= 0) {
            "Last terminal rotation elapsed millis must not be negative"
        }
        require(transitionGeneration >= 0) {
            "Rotation transition generation must not be negative"
        }
        require(!status.requiresRecordedTerminalTimestamp() || lastTerminalElapsedMillis != null) {
            "Terminal rotation status requires a recorded terminal timestamp"
        }
    }
}

private fun RotationStatus.requiresRecordedTerminalTimestamp(): Boolean =
    when (state) {
        RotationState.Completed -> true
        RotationState.Failed -> failureReason != RotationFailureReason.CooldownActive
        RotationState.Idle,
        RotationState.CheckingCooldown,
        RotationState.CheckingRoot,
        RotationState.ProbingOldPublicIp,
        RotationState.PausingNewRequests,
        RotationState.DrainingConnections,
        RotationState.RunningDisableCommand,
        RotationState.WaitingForToggleDelay,
        RotationState.RunningEnableCommand,
        RotationState.WaitingForNetworkReturn,
        RotationState.ProbingNewPublicIp,
        RotationState.ResumingProxyRequests,
        -> false
    }
