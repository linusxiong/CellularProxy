package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult

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

        return reconnectActiveSnapshot(
            expectedSnapshot = expectedSnapshot,
            credentials = credentials,
        )
    }

    fun reconnectIfActive(
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
        if (
            expectedSnapshot.status.state != CloudflareTunnelState.Connected &&
            expectedSnapshot.status.state != CloudflareTunnelState.Degraded
        ) {
            return CloudflareTunnelReconnectCoordinatorResult.NoAction(currentSnapshot)
        }

        return reconnectActiveSnapshot(
            expectedSnapshot = expectedSnapshot,
            credentials = credentials,
        )
    }

    private fun reconnectActiveSnapshot(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelReconnectCoordinatorResult {
        val connectionResult = connectToCloudflareEdge(connector, credentials)
        if (expectedSnapshot.status.state == CloudflareTunnelState.Connected) {
            return replaceConnectedSession(
                expectedSnapshot = expectedSnapshot,
                connectionResult = connectionResult,
            )
        }
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

    private fun replaceConnectedSession(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        connectionResult: CloudflareTunnelEdgeConnectionResult,
    ): CloudflareTunnelReconnectCoordinatorResult {
        var connectionToClose: CloudflareTunnelEdgeConnection? = null
        val result =
            synchronized(controlPlane) {
                val currentSnapshot = controlPlane.snapshot()
                if (currentSnapshot != expectedSnapshot) {
                    if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                        connectionToClose = connectionResult.connection
                    }
                    return@synchronized CloudflareTunnelReconnectCoordinatorResult.Stale(
                        expectedSnapshot = expectedSnapshot,
                        actualSnapshot = currentSnapshot,
                    )
                }

                if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                    connectionToClose =
                        sessionRegistry?.installConnectedSession(
                            snapshot = currentSnapshot,
                            connection = connectionResult.connection,
                        )
                    return@synchronized CloudflareTunnelReconnectCoordinatorResult.Applied(
                        connectionResult = connectionResult,
                        transition =
                            CloudflareTunnelControlPlaneTransitionResult(
                                transition =
                                    CloudflareTunnelTransitionResult(
                                        disposition = CloudflareTunnelTransitionDisposition.Accepted,
                                        status = currentSnapshot.status,
                                    ),
                                snapshot = currentSnapshot,
                            ),
                    )
                }

                when (val guarded = controlPlane.apply(expectedSnapshot, connectionResult.toCloudflareTunnelEvent())) {
                    is CloudflareTunnelGuardedTransitionResult.Evaluated ->
                        CloudflareTunnelReconnectCoordinatorResult.Applied(
                            connectionResult = connectionResult,
                            transition = guarded.transition,
                        )
                    is CloudflareTunnelGuardedTransitionResult.Stale ->
                        CloudflareTunnelReconnectCoordinatorResult.Stale(
                            expectedSnapshot = guarded.expectedSnapshot,
                            actualSnapshot = guarded.actualSnapshot,
                        )
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
