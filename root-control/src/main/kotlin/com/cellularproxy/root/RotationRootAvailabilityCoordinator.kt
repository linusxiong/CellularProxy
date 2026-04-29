package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState

class RotationRootAvailabilityCoordinator(
    private val availabilityController: RotationRootAvailabilityController,
    private val controlPlane: RotationControlPlane,
) {
    fun checkRootAvailability(
        expectedSnapshot: RotationControlPlaneSnapshot,
        timeoutMillis: Long,
        nowElapsedMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RotationRootAvailabilityAdvanceResult {
        require(timeoutMillis > 0) {
            "Rotation root availability timeout must be positive"
        }
        require(nowElapsedMillis >= 0) {
            "Rotation root availability observation elapsed millis must not be negative"
        }

        preflight(expectedSnapshot)?.let { return it }

        val decision =
            availabilityController.checkRoot(
                timeoutMillis = timeoutMillis,
                secrets = secrets,
            )

        return applyIfCurrent(
            expectedSnapshot = expectedSnapshot,
            decision = decision,
            nowElapsedMillis = nowElapsedMillis,
        )
    }

    private fun preflight(expectedSnapshot: RotationControlPlaneSnapshot): RotationRootAvailabilityAdvanceResult? = synchronized(controlPlane) {
        val actualSnapshot = controlPlane.snapshot()
        when {
            actualSnapshot != expectedSnapshot ->
                RotationRootAvailabilityAdvanceResult.Stale(
                    expectedSnapshot = expectedSnapshot,
                    actualSnapshot = actualSnapshot,
                )
            actualSnapshot.status.state != RotationState.CheckingRoot ->
                RotationRootAvailabilityAdvanceResult.NoAction(actualSnapshot)
            else -> null
        }
    }

    private fun applyIfCurrent(
        expectedSnapshot: RotationControlPlaneSnapshot,
        decision: RotationRootAvailabilityDecision,
        nowElapsedMillis: Long,
    ): RotationRootAvailabilityAdvanceResult = synchronized(controlPlane) {
        val actualSnapshot = controlPlane.snapshot()
        if (actualSnapshot != expectedSnapshot) {
            return RotationRootAvailabilityAdvanceResult.Stale(
                expectedSnapshot = expectedSnapshot,
                actualSnapshot = actualSnapshot,
                decision = decision,
            )
        }

        RotationRootAvailabilityAdvanceResult.Applied(
            decision = decision,
            progress =
                controlPlane.applyProgress(
                    event = decision.event,
                    nowElapsedMillis = nowElapsedMillis,
                ),
        )
    }
}

sealed interface RotationRootAvailabilityAdvanceResult {
    data class Applied(
        val decision: RotationRootAvailabilityDecision,
        val progress: RotationProgressGateResult,
    ) : RotationRootAvailabilityAdvanceResult {
        init {
            require(progress.transition.accepted) {
                "Applied root availability results require an accepted transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: RotationControlPlaneSnapshot,
        val actualSnapshot: RotationControlPlaneSnapshot,
        val decision: RotationRootAvailabilityDecision? = null,
    ) : RotationRootAvailabilityAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationRootAvailabilityAdvanceResult
}
