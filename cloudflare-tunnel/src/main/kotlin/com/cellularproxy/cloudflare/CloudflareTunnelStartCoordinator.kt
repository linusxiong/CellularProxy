package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition

class CloudflareTunnelStartCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
) {
    fun startIfEnabled(
        enabled: Boolean,
        rawTunnelToken: String?,
    ): CloudflareTunnelStartCoordinatorResult =
        when (val decision = CloudflareTunnelStartupPolicy.evaluate(enabled, rawTunnelToken)) {
            CloudflareTunnelStartupDecision.Disabled ->
                CloudflareTunnelStartCoordinatorResult.Disabled(controlPlane.snapshot())
            is CloudflareTunnelStartupDecision.Failed ->
                CloudflareTunnelStartCoordinatorResult.FailedStartup(decision.failure)
            is CloudflareTunnelStartupDecision.Ready ->
                CloudflareTunnelStartCoordinatorResult.Ready(
                    credentials = decision.credentials,
                    transition = controlPlane.apply(CloudflareTunnelEvent.StartRequested),
                )
        }
}

sealed interface CloudflareTunnelStartCoordinatorResult {
    class Ready(
        val credentials: CloudflareTunnelCredentials,
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelStartCoordinatorResult {
        init {
            require(transition.isStartRequestResult()) {
                "Ready Cloudflare tunnel start result must contain a start-request transition"
            }
        }

        override fun toString(): String =
            "CloudflareTunnelStartCoordinatorResult.Ready(" +
                "credentials=<redacted>, " +
                "transition=${transition.disposition}" +
                ")"
    }

    data class FailedStartup(
        val failure: CloudflareTunnelStartupFailure,
    ) : CloudflareTunnelStartCoordinatorResult

    data class Disabled(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelStartCoordinatorResult
}

private fun CloudflareTunnelControlPlaneTransitionResult.isStartRequestResult(): Boolean =
    when (disposition) {
        CloudflareTunnelTransitionDisposition.Accepted ->
            status.state == CloudflareTunnelState.Starting
        CloudflareTunnelTransitionDisposition.Duplicate ->
            status.state in ACTIVE_TUNNEL_STATES
        CloudflareTunnelTransitionDisposition.Ignored ->
            false
    }

private val ACTIVE_TUNNEL_STATES = setOf(
    CloudflareTunnelState.Starting,
    CloudflareTunnelState.Connected,
    CloudflareTunnelState.Degraded,
)
