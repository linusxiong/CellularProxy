package com.cellularproxy.shared.rotation

import kotlin.time.Duration

class RotationToggleDelayCoordinator(
    private val controlPlane: RotationControlPlane,
) {
    fun advance(
        expectedSnapshot: RotationControlPlaneSnapshot,
        delayStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        toggleDelay: Duration,
    ): RotationToggleDelayAdvanceResult {
        synchronized(controlPlane) {
            val actualSnapshot = controlPlane.snapshot()
            if (actualSnapshot != expectedSnapshot) {
                return RotationToggleDelayAdvanceResult.Stale(
                    expectedSnapshot = expectedSnapshot,
                    actualSnapshot = actualSnapshot,
                )
            }

            if (actualSnapshot.status.state != RotationState.WaitingForToggleDelay) {
                return RotationToggleDelayAdvanceResult.NoAction(actualSnapshot)
            }

            return when (
                val decision =
                    RotationToggleDelayPolicy.evaluate(
                        delayStartedElapsedMillis = delayStartedElapsedMillis,
                        nowElapsedMillis = nowElapsedMillis,
                        toggleDelay = toggleDelay,
                    )
            ) {
                RotationToggleDelayDecision.Elapsed ->
                    RotationToggleDelayAdvanceResult.Applied(
                        decision = RotationToggleDelayDecision.Elapsed,
                        progress =
                            controlPlane.applyProgress(
                                event = RotationEvent.ToggleDelayElapsed,
                                nowElapsedMillis = nowElapsedMillis,
                            ),
                    )
                is RotationToggleDelayDecision.Waiting ->
                    RotationToggleDelayAdvanceResult.Waiting(
                        decision = decision,
                        snapshot = controlPlane.snapshot(),
                    )
            }
        }
    }
}

sealed interface RotationToggleDelayAdvanceResult {
    data class Applied(
        val decision: RotationToggleDelayDecision.Elapsed,
        val progress: RotationProgressGateResult,
    ) : RotationToggleDelayAdvanceResult

    data class Waiting(
        val decision: RotationToggleDelayDecision.Waiting,
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationToggleDelayAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationToggleDelayAdvanceResult

    data class Stale(
        val expectedSnapshot: RotationControlPlaneSnapshot,
        val actualSnapshot: RotationControlPlaneSnapshot,
    ) : RotationToggleDelayAdvanceResult
}
