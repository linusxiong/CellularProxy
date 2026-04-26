package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlin.time.Duration

class RotationNetworkReturnCoordinator(
    private val controlPlane: RotationControlPlane,
) {
    fun advance(
        expectedSnapshot: RotationControlPlaneSnapshot,
        routeTarget: RouteTarget,
        networks: List<NetworkDescriptor>,
        waitStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        networkReturnTimeout: Duration,
    ): RotationNetworkReturnAdvanceResult {
        synchronized(controlPlane) {
            val actualSnapshot = controlPlane.snapshot()
            if (actualSnapshot != expectedSnapshot) {
                return RotationNetworkReturnAdvanceResult.Stale(
                    expectedSnapshot = expectedSnapshot,
                    actualSnapshot = actualSnapshot,
                )
            }

            if (actualSnapshot.status.state != RotationState.WaitingForNetworkReturn) {
                return RotationNetworkReturnAdvanceResult.NoAction(actualSnapshot)
            }

            return when (
                val decision = RotationNetworkReturnPolicy.evaluate(
                    routeTarget = routeTarget,
                    networks = networks,
                    waitStartedElapsedMillis = waitStartedElapsedMillis,
                    nowElapsedMillis = nowElapsedMillis,
                    networkReturnTimeout = networkReturnTimeout,
                )
            ) {
                is RotationNetworkReturnDecision.Returned ->
                    RotationNetworkReturnAdvanceResult.Applied(
                        decision = decision,
                        progress = controlPlane.applyProgress(
                            event = RotationEvent.NetworkReturned,
                            nowElapsedMillis = nowElapsedMillis,
                        ),
                    )
                is RotationNetworkReturnDecision.TimedOut ->
                    RotationNetworkReturnAdvanceResult.Applied(
                        decision = decision,
                        progress = controlPlane.applyProgress(
                            event = RotationEvent.NetworkReturnTimedOut,
                            nowElapsedMillis = nowElapsedMillis,
                        ),
                    )
                is RotationNetworkReturnDecision.Waiting ->
                    RotationNetworkReturnAdvanceResult.Waiting(
                        decision = decision,
                        snapshot = controlPlane.snapshot(),
                    )
            }
        }
    }
}

sealed interface RotationNetworkReturnAdvanceResult {
    data class Applied(
        val decision: RotationNetworkReturnDecision,
        val progress: RotationProgressGateResult,
    ) : RotationNetworkReturnAdvanceResult {
        init {
            require(decision.event != null) {
                "Applied network return results require a transition event"
            }
            require(progress.transition.accepted) {
                "Applied network return results require an accepted transition"
            }
        }
    }

    data class Waiting(
        val decision: RotationNetworkReturnDecision.Waiting,
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationNetworkReturnAdvanceResult

    data class NoAction(
        val snapshot: RotationControlPlaneSnapshot,
    ) : RotationNetworkReturnAdvanceResult

    data class Stale(
        val expectedSnapshot: RotationControlPlaneSnapshot,
        val actualSnapshot: RotationControlPlaneSnapshot,
    ) : RotationNetworkReturnAdvanceResult
}
