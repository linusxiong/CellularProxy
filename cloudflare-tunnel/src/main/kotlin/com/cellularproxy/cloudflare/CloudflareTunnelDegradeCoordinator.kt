package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus

class CloudflareTunnelDegradeCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    private val sessionRegistry: CloudflareTunnelEdgeCurrentSessionStore,
) {
    fun markDegradedIfCurrent(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        activeConnection: CloudflareTunnelEdgeConnection,
    ): CloudflareTunnelDegradeCoordinatorResult =
        synchronized(controlPlane) {
            val currentSnapshot = controlPlane.snapshot()
            if (currentSnapshot != expectedSnapshot) {
                return@synchronized CloudflareTunnelDegradeCoordinatorResult.Stale(
                    expectedSnapshot = expectedSnapshot,
                    actualSnapshot = currentSnapshot,
                )
            }
            if (expectedSnapshot.status.state != CloudflareTunnelState.Connected) {
                return@synchronized CloudflareTunnelDegradeCoordinatorResult.NoAction(currentSnapshot)
            }
            if (!sessionRegistry.isCurrent(expectedSnapshot, activeConnection)) {
                return@synchronized CloudflareTunnelDegradeCoordinatorResult.ActiveSessionMismatch(currentSnapshot)
            }

            when (val guarded = controlPlane.apply(expectedSnapshot, CloudflareTunnelEvent.Degraded)) {
                is CloudflareTunnelGuardedTransitionResult.Evaluated -> {
                    check(
                        sessionRegistry.updateSnapshotIfCurrent(
                            currentSnapshot = expectedSnapshot,
                            newSnapshot = guarded.transition.snapshot,
                            connection = activeConnection,
                        ),
                    ) {
                        "Active Cloudflare tunnel edge session changed while applying degradation"
                    }
                    CloudflareTunnelDegradeCoordinatorResult.Applied(guarded.transition)
                }
                is CloudflareTunnelGuardedTransitionResult.Stale ->
                    CloudflareTunnelDegradeCoordinatorResult.Stale(
                        expectedSnapshot = guarded.expectedSnapshot,
                        actualSnapshot = guarded.actualSnapshot,
                    )
            }
        }
}

sealed interface CloudflareTunnelDegradeCoordinatorResult {
    data class Applied(
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelDegradeCoordinatorResult {
        init {
            require(
                transition.accepted &&
                    transition.status == CloudflareTunnelStatus.degraded(),
            ) {
                "Applied Cloudflare tunnel degradation result must contain an accepted degraded transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelDegradeCoordinatorResult

    data class ActiveSessionMismatch(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelDegradeCoordinatorResult

    data class NoAction(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelDegradeCoordinatorResult
}
