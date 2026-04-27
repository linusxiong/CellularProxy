package com.cellularproxy.shared.cloudflare

data class CloudflareTunnelStatus(
    val state: CloudflareTunnelState,
    val failureReason: String? = null,
) {
    init {
        if (state == CloudflareTunnelState.Failed) {
            require(!failureReason.isNullOrBlank()) {
                "Failed Cloudflare tunnel status requires a non-blank failure reason"
            }
        } else {
            require(failureReason == null) {
                "Cloudflare tunnel failure reason is only valid for failed status"
            }
        }
    }

    val isRemoteManagementAvailable: Boolean
        get() = state == CloudflareTunnelState.Connected

    companion object {
        fun disabled(): CloudflareTunnelStatus = CloudflareTunnelStatus(CloudflareTunnelState.Disabled)

        fun starting(): CloudflareTunnelStatus = CloudflareTunnelStatus(CloudflareTunnelState.Starting)

        fun connected(): CloudflareTunnelStatus = CloudflareTunnelStatus(CloudflareTunnelState.Connected)

        fun degraded(): CloudflareTunnelStatus = CloudflareTunnelStatus(CloudflareTunnelState.Degraded)

        fun stopped(): CloudflareTunnelStatus = CloudflareTunnelStatus(CloudflareTunnelState.Stopped)

        fun failed(reason: String): CloudflareTunnelStatus =
            CloudflareTunnelStatus(
                state = CloudflareTunnelState.Failed,
                failureReason = reason,
            )
    }
}

enum class CloudflareTunnelState {
    Disabled,
    Starting,
    Connected,
    Degraded,
    Stopped,
    Failed,
}

sealed interface CloudflareTunnelEvent {
    data object StartRequested : CloudflareTunnelEvent

    data object Connected : CloudflareTunnelEvent

    data object Degraded : CloudflareTunnelEvent

    data class Failed(
        val reason: String,
    ) : CloudflareTunnelEvent {
        init {
            require(reason.isNotBlank()) { "Cloudflare tunnel failure reason must not be blank" }
        }
    }

    data object StopRequested : CloudflareTunnelEvent

    data object DisableRequested : CloudflareTunnelEvent
}

data class CloudflareTunnelTransitionResult(
    val disposition: CloudflareTunnelTransitionDisposition,
    val status: CloudflareTunnelStatus,
) {
    val accepted: Boolean
        get() = disposition == CloudflareTunnelTransitionDisposition.Accepted
}

enum class CloudflareTunnelTransitionDisposition {
    Accepted,
    Duplicate,
    Ignored,
}

object CloudflareTunnelStateMachine {
    fun transition(
        current: CloudflareTunnelStatus,
        event: CloudflareTunnelEvent,
    ): CloudflareTunnelTransitionResult =
        when (event) {
            CloudflareTunnelEvent.StartRequested -> start(current)
            CloudflareTunnelEvent.Connected -> connected(current)
            CloudflareTunnelEvent.Degraded -> degraded(current)
            is CloudflareTunnelEvent.Failed -> failed(current, event.reason)
            CloudflareTunnelEvent.StopRequested -> stop(current)
            CloudflareTunnelEvent.DisableRequested -> disable(current)
        }

    private fun start(current: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            CloudflareTunnelState.Failed,
            -> accepted(CloudflareTunnelStatus.starting())
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            -> duplicate(current)
        }

    private fun connected(current: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Degraded,
            -> accepted(CloudflareTunnelStatus.connected())
            CloudflareTunnelState.Connected -> duplicate(current)
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            CloudflareTunnelState.Failed,
            -> ignored(current)
        }

    private fun degraded(current: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            -> accepted(CloudflareTunnelStatus.degraded())
            CloudflareTunnelState.Degraded -> duplicate(current)
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            CloudflareTunnelState.Failed,
            -> ignored(current)
        }

    private fun failed(
        current: CloudflareTunnelStatus,
        reason: String,
    ): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            -> accepted(CloudflareTunnelStatus.failed(reason))
            CloudflareTunnelState.Failed -> duplicate(current)
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            -> ignored(current)
        }

    private fun stop(current: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            CloudflareTunnelState.Failed,
            -> accepted(CloudflareTunnelStatus.stopped())
            CloudflareTunnelState.Stopped -> duplicate(current)
            CloudflareTunnelState.Disabled -> ignored(current)
        }

    private fun disable(current: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        when (current.state) {
            CloudflareTunnelState.Disabled -> duplicate(current)
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            CloudflareTunnelState.Stopped,
            CloudflareTunnelState.Failed,
            -> accepted(CloudflareTunnelStatus.disabled())
        }

    private fun accepted(status: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        CloudflareTunnelTransitionResult(CloudflareTunnelTransitionDisposition.Accepted, status)

    private fun duplicate(status: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        CloudflareTunnelTransitionResult(CloudflareTunnelTransitionDisposition.Duplicate, status)

    private fun ignored(status: CloudflareTunnelStatus): CloudflareTunnelTransitionResult =
        CloudflareTunnelTransitionResult(CloudflareTunnelTransitionDisposition.Ignored, status)
}
