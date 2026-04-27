package com.cellularproxy.proxy.server

import com.cellularproxy.shared.rotation.RotationConnectionDrainDecision
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState
import kotlin.time.Duration

class ProxyRotationConnectionDrainCoordinator(
    private val drainController: ProxyRotationConnectionDrainController,
    private val controlPlane: RotationControlPlane,
) {
    fun advance(
        drainStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        maxDrainTime: Duration,
    ): ProxyRotationConnectionDrainAdvanceResult {
        synchronized(controlPlane) {
            if (controlPlane.currentStatus.state != RotationState.DrainingConnections) {
                return ProxyRotationConnectionDrainAdvanceResult.NoAction(controlPlane.snapshot())
            }

            val decision =
                drainController.evaluate(
                    drainStartedElapsedMillis = drainStartedElapsedMillis,
                    nowElapsedMillis = nowElapsedMillis,
                    maxDrainTime = maxDrainTime,
                )

            return when (decision) {
                is RotationConnectionDrainDecision.Drained ->
                    ProxyRotationConnectionDrainAdvanceResult.Applied(
                        decision = decision,
                        progress =
                            controlPlane.applyProgress(
                                event = decision.event,
                                nowElapsedMillis = nowElapsedMillis,
                            ),
                    )
                is RotationConnectionDrainDecision.Waiting ->
                    ProxyRotationConnectionDrainAdvanceResult.Waiting(
                        decision = decision,
                        snapshot = controlPlane.snapshot(),
                    )
            }
        }
    }
}

sealed interface ProxyRotationConnectionDrainAdvanceResult {
    data class Applied(
        val decision: RotationConnectionDrainDecision.Drained,
        val progress: RotationProgressGateResult,
    ) : ProxyRotationConnectionDrainAdvanceResult

    data class Waiting(
        val decision: RotationConnectionDrainDecision.Waiting,
        val snapshot: RotationControlPlaneSnapshot,
    ) : ProxyRotationConnectionDrainAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : ProxyRotationConnectionDrainAdvanceResult
}
