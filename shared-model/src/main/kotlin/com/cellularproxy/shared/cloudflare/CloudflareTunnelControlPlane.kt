package com.cellularproxy.shared.cloudflare

class CloudflareTunnelControlPlane(
    initialStatus: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled(),
) {
    private var status: CloudflareTunnelStatus = initialStatus
    private var transitionGeneration: Long = 0

    @get:Synchronized
    val currentStatus: CloudflareTunnelStatus
        get() = status

    @Synchronized
    fun apply(event: CloudflareTunnelEvent): CloudflareTunnelControlPlaneTransitionResult =
        applyLocked(event)

    @Synchronized
    fun apply(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        event: CloudflareTunnelEvent,
    ): CloudflareTunnelGuardedTransitionResult {
        val actualSnapshot = snapshotLocked()
        if (actualSnapshot != expectedSnapshot) {
            return CloudflareTunnelGuardedTransitionResult.Stale(
                expectedSnapshot = expectedSnapshot,
                actualSnapshot = actualSnapshot,
            )
        }

        return CloudflareTunnelGuardedTransitionResult.Evaluated(applyLocked(event))
    }

    @Synchronized
    fun snapshot(): CloudflareTunnelControlPlaneSnapshot = snapshotLocked()

    private fun applyLocked(event: CloudflareTunnelEvent): CloudflareTunnelControlPlaneTransitionResult {
        val result = CloudflareTunnelStateMachine.transition(status, event)
        if (result.accepted) {
            status = result.status
            transitionGeneration += 1
        }
        return CloudflareTunnelControlPlaneTransitionResult(
            transition = result,
            snapshot = snapshotLocked(),
        )
    }

    private fun snapshotLocked(): CloudflareTunnelControlPlaneSnapshot =
        CloudflareTunnelControlPlaneSnapshot(
            status = status,
            transitionGeneration = transitionGeneration,
        )
}

data class CloudflareTunnelControlPlaneTransitionResult(
    val transition: CloudflareTunnelTransitionResult,
    val snapshot: CloudflareTunnelControlPlaneSnapshot,
) {
    init {
        require(transition.status == snapshot.status) {
            "Cloudflare tunnel transition result status must match the committed snapshot"
        }
    }

    val disposition: CloudflareTunnelTransitionDisposition
        get() = transition.disposition

    val status: CloudflareTunnelStatus
        get() = transition.status

    val accepted: Boolean
        get() = transition.accepted
}

sealed interface CloudflareTunnelGuardedTransitionResult {
    data class Evaluated(
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelGuardedTransitionResult

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelGuardedTransitionResult
}

data class CloudflareTunnelControlPlaneSnapshot(
    val status: CloudflareTunnelStatus,
    val transitionGeneration: Long = 0,
) {
    init {
        require(transitionGeneration >= 0) {
            "Cloudflare tunnel transition generation must not be negative"
        }
    }
}
