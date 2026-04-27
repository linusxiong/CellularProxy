package com.cellularproxy.shared.rotation

class RotationProgressGate(
    private val sessionController: RotationSessionController,
    private val terminalTimestampTracker: TerminalRotationTimestampTracker,
) {
    fun apply(
        event: RotationEvent,
        nowElapsedMillis: Long,
    ): RotationProgressGateResult {
        require(!event.requiresStartGate()) {
            "Rotation start and cooldown events must use RotationStartGate"
        }
        require(nowElapsedMillis >= 0) {
            "Progress observation elapsed millis must not be negative"
        }

        val transition = sessionController.apply(event)
        val terminalTimestampObservation =
            terminalTimestampTracker.observe(
                transition = transition,
                nowElapsedMillis = nowElapsedMillis,
            )

        return RotationProgressGateResult(
            transition = transition,
            terminalTimestampObservation = terminalTimestampObservation,
        )
    }
}

data class RotationProgressGateResult(
    val transition: RotationTransitionResult,
    val terminalTimestampObservation: TerminalRotationTimestampObservation,
) {
    val status: RotationStatus
        get() = transition.status
}

private fun RotationEvent.requiresStartGate(): Boolean =
    when (this) {
        is RotationEvent.StartRequested,
        RotationEvent.CooldownPassed,
        RotationEvent.CooldownRejected,
        -> true
        RotationEvent.RootAvailable,
        RotationEvent.RootUnavailable,
        is RotationEvent.OldPublicIpProbeSucceeded,
        RotationEvent.OldPublicIpProbeFailed,
        RotationEvent.NewRequestsPaused,
        RotationEvent.ConnectionsDrained,
        is RotationEvent.RootCommandCompleted,
        is RotationEvent.RootCommandFailedToStart,
        RotationEvent.ToggleDelayElapsed,
        RotationEvent.NetworkReturned,
        RotationEvent.NetworkReturnTimedOut,
        is RotationEvent.NewPublicIpProbeSucceeded,
        RotationEvent.NewPublicIpProbeFailed,
        RotationEvent.ProxyRequestsResumed,
        -> false
    }
