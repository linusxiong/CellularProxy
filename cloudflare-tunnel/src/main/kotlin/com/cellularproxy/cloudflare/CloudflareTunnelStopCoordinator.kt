package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState

class CloudflareTunnelStopCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    private val sessionRegistry: CloudflareTunnelEdgeSessionStore? = null,
) {
    fun stopIfCurrent(expectedSnapshot: CloudflareTunnelControlPlaneSnapshot): CloudflareTunnelStopCoordinatorResult =
        stopIfCurrent(
            expectedSnapshot = expectedSnapshot,
            activeConnectionProvider = { sessionRegistry?.takeActiveConnection() },
        )

    fun stopIfCurrent(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        activeConnection: CloudflareTunnelEdgeConnection?,
    ): CloudflareTunnelStopCoordinatorResult =
        stopIfCurrent(
            expectedSnapshot = expectedSnapshot,
            activeConnectionProvider = { activeConnection },
        )

    private fun stopIfCurrent(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        activeConnectionProvider: () -> CloudflareTunnelEdgeConnection?,
    ): CloudflareTunnelStopCoordinatorResult {
        var connectionToClose: CloudflareTunnelEdgeConnection? = null
        val result =
            synchronized(controlPlane) {
                val currentSnapshot = controlPlane.snapshot()
                if (currentSnapshot != expectedSnapshot) {
                    return@synchronized CloudflareTunnelStopCoordinatorResult.Stale(
                        expectedSnapshot = expectedSnapshot,
                        actualSnapshot = currentSnapshot,
                    )
                }
                if (!expectedSnapshot.status.state.isStoppableTunnelState()) {
                    return@synchronized CloudflareTunnelStopCoordinatorResult.NoAction(currentSnapshot)
                }

                connectionToClose = activeConnectionProvider()

                when (val guarded = controlPlane.apply(expectedSnapshot, CloudflareTunnelEvent.StopRequested)) {
                    is CloudflareTunnelGuardedTransitionResult.Evaluated ->
                        CloudflareTunnelStopCoordinatorResult.Applied(guarded.transition)
                    is CloudflareTunnelGuardedTransitionResult.Stale ->
                        CloudflareTunnelStopCoordinatorResult.Stale(
                            expectedSnapshot = guarded.expectedSnapshot,
                            actualSnapshot = guarded.actualSnapshot,
                        )
                }
            }
        connectionToClose?.closeSuppressingExceptions()
        return result
    }

    private fun CloudflareTunnelState.isStoppableTunnelState(): Boolean =
        when (this) {
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            CloudflareTunnelState.Failed,
            -> true
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            -> false
        }
}

sealed interface CloudflareTunnelStopCoordinatorResult {
    data class Applied(
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelStopCoordinatorResult {
        init {
            require(transition.accepted) {
                "Applied Cloudflare tunnel stop result must contain an accepted transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelStopCoordinatorResult

    data class NoAction(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelStopCoordinatorResult
}
