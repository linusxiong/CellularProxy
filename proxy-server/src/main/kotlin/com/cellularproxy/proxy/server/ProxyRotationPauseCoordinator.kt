package com.cellularproxy.proxy.server

import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState

class ProxyRotationPauseCoordinator(
    private val pauseController: ProxyRotationPauseActions,
    private val controlPlane: RotationControlPlane,
) {
    fun advance(nowElapsedMillis: Long): ProxyRotationPauseAdvanceResult {
        require(nowElapsedMillis >= 0) {
            "Pause or resume observation elapsed millis must not be negative"
        }

        synchronized(controlPlane) {
            val event = when (controlPlane.currentStatus.state) {
                RotationState.PausingNewRequests -> pauseController.pauseProxyRequests()
                RotationState.ResumingProxyRequests -> pauseController.resumeProxyRequests()
                else -> return ProxyRotationPauseAdvanceResult.NoAction(controlPlane.snapshot())
            }

            return ProxyRotationPauseAdvanceResult.Applied(
                event = event,
                progress = controlPlane.applyProgress(
                    event = event,
                    nowElapsedMillis = nowElapsedMillis,
                ),
            )
        }
    }
}

sealed interface ProxyRotationPauseAdvanceResult {
    data class Applied(
        val event: RotationEvent,
        val progress: RotationProgressGateResult,
    ) : ProxyRotationPauseAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : ProxyRotationPauseAdvanceResult
}
