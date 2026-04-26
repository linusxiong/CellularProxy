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
    private val terminalTimestampTracker = TerminalRotationTimestampTracker(
        initialLastTerminalElapsedMillis = initialLastTerminalElapsedMillis,
    )
    private val startGate = RotationStartGate(
        sessionController = sessionController,
        terminalTimestampTracker = terminalTimestampTracker,
    )
    private val progressGate = RotationProgressGate(
        sessionController = sessionController,
        terminalTimestampTracker = terminalTimestampTracker,
    )

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
    ): RotationStartGateResult =
        startGate.requestStart(
            operation = operation,
            nowElapsedMillis = nowElapsedMillis,
            cooldown = cooldown,
        )

    @Synchronized
    fun applyProgress(
        event: RotationEvent,
        nowElapsedMillis: Long,
    ): RotationProgressGateResult =
        progressGate.apply(
            event = event,
            nowElapsedMillis = nowElapsedMillis,
        )

    @Synchronized
    fun snapshot(): RotationControlPlaneSnapshot =
        RotationControlPlaneSnapshot(
            status = sessionController.currentStatus,
            lastTerminalElapsedMillis = terminalTimestampTracker.lastTerminalElapsedMillis,
        )
}

data class RotationControlPlaneSnapshot(
    val status: RotationStatus,
    val lastTerminalElapsedMillis: Long?,
) {
    init {
        require(lastTerminalElapsedMillis == null || lastTerminalElapsedMillis >= 0) {
            "Last terminal rotation elapsed millis must not be negative"
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
