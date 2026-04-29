package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState

class RotationPublicIpProbeCoordinator(
    private val probeController: RotationPublicIpProbeController,
    private val controlPlane: RotationControlPlane,
) {
    suspend fun probeOldPublicIp(
        expectedSnapshot: RotationControlPlaneSnapshot,
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
        nowElapsedMillis: Long,
    ): RotationPublicIpProbeAdvanceResult {
        preflight(
            expectedSnapshot = expectedSnapshot,
            requiredState = RotationState.ProbingOldPublicIp,
        )?.let { return it }

        require(nowElapsedMillis >= 0) {
            "Rotation public IP probe observation elapsed millis must not be negative"
        }

        val decision =
            probeController.probeOldPublicIp(
                route = route,
                endpoint = endpoint,
            )
        return applyIfCurrent(
            expectedSnapshot = expectedSnapshot,
            decision = decision,
            nowElapsedMillis = nowElapsedMillis,
        )
    }

    suspend fun probeNewPublicIp(
        expectedSnapshot: RotationControlPlaneSnapshot,
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
        strictIpChangeRequired: Boolean,
        nowElapsedMillis: Long,
    ): RotationPublicIpProbeAdvanceResult {
        preflight(
            expectedSnapshot = expectedSnapshot,
            requiredState = RotationState.ProbingNewPublicIp,
        )?.let { return it }

        require(nowElapsedMillis >= 0) {
            "Rotation public IP probe observation elapsed millis must not be negative"
        }

        val decision =
            probeController.probeNewPublicIp(
                route = route,
                endpoint = endpoint,
                strictIpChangeRequired = strictIpChangeRequired,
            )
        return applyIfCurrent(
            expectedSnapshot = expectedSnapshot,
            decision = decision,
            nowElapsedMillis = nowElapsedMillis,
        )
    }

    private fun preflight(
        expectedSnapshot: RotationControlPlaneSnapshot,
        requiredState: RotationState,
    ): RotationPublicIpProbeAdvanceResult? = synchronized(controlPlane) {
        val actualSnapshot = controlPlane.snapshot()
        when {
            actualSnapshot != expectedSnapshot ->
                RotationPublicIpProbeAdvanceResult.Stale(
                    expectedSnapshot = expectedSnapshot,
                    actualSnapshot = actualSnapshot,
                )
            actualSnapshot.status.state != requiredState ->
                RotationPublicIpProbeAdvanceResult.NoAction(actualSnapshot)
            else -> null
        }
    }

    private fun applyIfCurrent(
        expectedSnapshot: RotationControlPlaneSnapshot,
        decision: RotationPublicIpProbeDecision,
        nowElapsedMillis: Long,
    ): RotationPublicIpProbeAdvanceResult = synchronized(controlPlane) {
        val actualSnapshot = controlPlane.snapshot()
        if (actualSnapshot != expectedSnapshot) {
            return RotationPublicIpProbeAdvanceResult.Stale(
                expectedSnapshot = expectedSnapshot,
                actualSnapshot = actualSnapshot,
                decision = decision,
            )
        }

        RotationPublicIpProbeAdvanceResult.Applied(
            decision = decision,
            progress =
                controlPlane.applyProgress(
                    event = decision.event,
                    nowElapsedMillis = nowElapsedMillis,
                ),
        )
    }
}

sealed interface RotationPublicIpProbeAdvanceResult {
    data class Applied(
        val decision: RotationPublicIpProbeDecision,
        val progress: RotationProgressGateResult,
    ) : RotationPublicIpProbeAdvanceResult {
        init {
            require(progress.transition.accepted) {
                "Applied public IP probe results require an accepted transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: RotationControlPlaneSnapshot,
        val actualSnapshot: RotationControlPlaneSnapshot,
        val decision: RotationPublicIpProbeDecision? = null,
    ) : RotationPublicIpProbeAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationPublicIpProbeAdvanceResult
}
