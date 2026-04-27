package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState

class CloudflareTunnelReconnectCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    private val connector: CloudflareTunnelEdgeConnector,
    private val sessionRegistry: CloudflareTunnelEdgeSessionStore? = null,
) {
    fun reconnectIfDegraded(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelReconnectCoordinatorResult {
        val currentSnapshot = controlPlane.snapshot()
        if (currentSnapshot != expectedSnapshot) {
            return CloudflareTunnelReconnectCoordinatorResult.Stale(
                expectedSnapshot = expectedSnapshot,
                actualSnapshot = currentSnapshot,
            )
        }
        if (expectedSnapshot.status.state != CloudflareTunnelState.Degraded) {
            return CloudflareTunnelReconnectCoordinatorResult.NoAction(currentSnapshot)
        }

        val connectionResult = connectToCloudflareEdge(connector, credentials)
        val event = connectionResult.toCloudflareTunnelEvent()

        var connectionToClose: CloudflareTunnelEdgeConnection? = null
        val result =
            synchronized(controlPlane) {
                when (val guarded = controlPlane.apply(expectedSnapshot, event)) {
                    is CloudflareTunnelGuardedTransitionResult.Evaluated -> {
                        if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                            connectionToClose =
                                sessionRegistry?.installConnectedSession(
                                    snapshot = guarded.transition.snapshot,
                                    connection = connectionResult.connection,
                                )
                        }
                        CloudflareTunnelReconnectCoordinatorResult.Applied(
                            connectionResult = connectionResult,
                            transition = guarded.transition,
                        )
                    }
                    is CloudflareTunnelGuardedTransitionResult.Stale -> {
                        if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                            connectionToClose = connectionResult.connection
                        }
                        CloudflareTunnelReconnectCoordinatorResult.Stale(
                            expectedSnapshot = guarded.expectedSnapshot,
                            actualSnapshot = guarded.actualSnapshot,
                        )
                    }
                }
            }
        connectionToClose?.closeSuppressingExceptions()
        return result
    }
}

sealed interface CloudflareTunnelReconnectCoordinatorResult {
    data class Applied(
        val connectionResult: CloudflareTunnelEdgeConnectionResult,
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelReconnectCoordinatorResult {
        init {
            require(transition.accepted) {
                "Applied Cloudflare tunnel reconnect result must contain an accepted transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelReconnectCoordinatorResult

    data class NoAction(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelReconnectCoordinatorResult
}
