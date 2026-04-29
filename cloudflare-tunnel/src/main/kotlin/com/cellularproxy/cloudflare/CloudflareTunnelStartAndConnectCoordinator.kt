package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition

class CloudflareTunnelStartAndConnectCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    connector: CloudflareTunnelEdgeConnector,
    sessionRegistry: CloudflareTunnelEdgeSessionStore? = null,
) {
    private val startCoordinator = CloudflareTunnelStartCoordinator(controlPlane)
    private val connectionCoordinator =
        CloudflareTunnelConnectionCoordinator(
            controlPlane = controlPlane,
            connector = connector,
            sessionRegistry = sessionRegistry,
        )

    fun startAndConnectIfEnabled(
        enabled: Boolean,
        rawTunnelToken: String?,
    ): CloudflareTunnelStartAndConnectCoordinatorResult = when (val startResult = startCoordinator.startIfEnabled(enabled, rawTunnelToken)) {
        is CloudflareTunnelStartCoordinatorResult.Ready ->
            if (startResult.transition.isAcceptedStarting()) {
                CloudflareTunnelStartAndConnectCoordinatorResult.ConnectionAttempted(
                    startTransition = startResult.transition,
                    connectionResult =
                        connectionCoordinator.connectIfStarting(
                            expectedSnapshot = startResult.transition.snapshot,
                            credentials = startResult.credentials,
                        ),
                )
            } else {
                CloudflareTunnelStartAndConnectCoordinatorResult.NoConnectionAttempt(startResult)
            }
        is CloudflareTunnelStartCoordinatorResult.Disabled,
        is CloudflareTunnelStartCoordinatorResult.FailedStartup,
        -> CloudflareTunnelStartAndConnectCoordinatorResult.NoConnectionAttempt(startResult)
    }
}

sealed interface CloudflareTunnelStartAndConnectCoordinatorResult {
    data class ConnectionAttempted(
        val startTransition: CloudflareTunnelControlPlaneTransitionResult,
        val connectionResult: CloudflareTunnelConnectionCoordinatorResult,
    ) : CloudflareTunnelStartAndConnectCoordinatorResult {
        init {
            require(startTransition.isAcceptedStarting()) {
                "Connection-attempted Cloudflare tunnel result must contain an accepted starting transition"
            }
        }
    }

    data class NoConnectionAttempt(
        val startResult: CloudflareTunnelStartCoordinatorResult,
    ) : CloudflareTunnelStartAndConnectCoordinatorResult {
        init {
            require(
                startResult !is CloudflareTunnelStartCoordinatorResult.Ready ||
                    !startResult.transition.isAcceptedStarting(),
            ) {
                "No-connection-attempt Cloudflare tunnel result cannot contain an accepted starting transition"
            }
        }
    }
}

private fun CloudflareTunnelControlPlaneTransitionResult.isAcceptedStarting(): Boolean = disposition == CloudflareTunnelTransitionDisposition.Accepted &&
    status.state == CloudflareTunnelState.Starting
