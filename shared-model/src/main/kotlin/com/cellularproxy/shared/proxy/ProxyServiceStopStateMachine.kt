package com.cellularproxy.shared.proxy

sealed interface ProxyServiceStopEvent {
    data object StopRequested : ProxyServiceStopEvent
}

data class ProxyServiceStopTransitionResult(
    val disposition: ProxyServiceStopTransitionDisposition,
    val status: ProxyServiceStatus,
) {
    val accepted: Boolean
        get() = disposition == ProxyServiceStopTransitionDisposition.Accepted
}

enum class ProxyServiceStopTransitionDisposition {
    Accepted,
    Duplicate,
    Ignored,
}

object ProxyServiceStopStateMachine {
    fun transition(
        current: ProxyServiceStatus,
        event: ProxyServiceStopEvent,
    ): ProxyServiceStopTransitionResult = when (event) {
        ProxyServiceStopEvent.StopRequested -> stop(current)
    }

    private fun stop(current: ProxyServiceStatus): ProxyServiceStopTransitionResult = when (current.state) {
        ProxyServiceState.Starting,
        ProxyServiceState.Running,
        -> accepted(current.copy(state = ProxyServiceState.Stopping))
        ProxyServiceState.Stopping -> duplicate(current)
        ProxyServiceState.Stopped,
        ProxyServiceState.Failed,
        -> ignored(current)
    }

    private fun accepted(status: ProxyServiceStatus): ProxyServiceStopTransitionResult = ProxyServiceStopTransitionResult(ProxyServiceStopTransitionDisposition.Accepted, status)

    private fun duplicate(status: ProxyServiceStatus): ProxyServiceStopTransitionResult = ProxyServiceStopTransitionResult(ProxyServiceStopTransitionDisposition.Duplicate, status)

    private fun ignored(status: ProxyServiceStatus): ProxyServiceStopTransitionResult = ProxyServiceStopTransitionResult(ProxyServiceStopTransitionDisposition.Ignored, status)
}
