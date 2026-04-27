package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition

class CloudflareTunnelDisableCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    private val sessionRegistry: CloudflareTunnelEdgeSessionStore? = null,
) {
    fun disableIfCurrent(expectedSnapshot: CloudflareTunnelControlPlaneSnapshot): CloudflareTunnelDisableCoordinatorResult {
        var connectionToClose: CloudflareTunnelEdgeConnection? = null
        val result =
            synchronized(controlPlane) {
                val currentSnapshot = controlPlane.snapshot()
                if (currentSnapshot != expectedSnapshot) {
                    return@synchronized CloudflareTunnelDisableCoordinatorResult.Stale(
                        expectedSnapshot = expectedSnapshot,
                        actualSnapshot = currentSnapshot,
                    )
                }

                connectionToClose = sessionRegistry?.takeActiveConnection()

                when (val guarded = controlPlane.apply(expectedSnapshot, CloudflareTunnelEvent.DisableRequested)) {
                    is CloudflareTunnelGuardedTransitionResult.Evaluated ->
                        CloudflareTunnelDisableCoordinatorResult.Applied(guarded.transition)
                    is CloudflareTunnelGuardedTransitionResult.Stale ->
                        CloudflareTunnelDisableCoordinatorResult.Stale(
                            expectedSnapshot = guarded.expectedSnapshot,
                            actualSnapshot = guarded.actualSnapshot,
                        )
                }
            }
        connectionToClose?.closeSuppressingExceptions()
        return result
    }
}

sealed interface CloudflareTunnelDisableCoordinatorResult {
    data class Applied(
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelDisableCoordinatorResult {
        init {
            require(transition.isDisableRequestResult()) {
                "Applied Cloudflare tunnel disable result must contain a disabled transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelDisableCoordinatorResult
}

private fun CloudflareTunnelControlPlaneTransitionResult.isDisableRequestResult(): Boolean =
    status == CloudflareTunnelStatus.disabled() &&
        disposition in DISABLE_REQUEST_DISPOSITIONS

private val DISABLE_REQUEST_DISPOSITIONS =
    setOf(
        CloudflareTunnelTransitionDisposition.Accepted,
        CloudflareTunnelTransitionDisposition.Duplicate,
    )
