package com.cellularproxy.proxy.metrics

import com.cellularproxy.shared.proxy.ProxyTrafficMetrics

sealed interface ProxyTrafficMetricsEvent {
    data object ConnectionAccepted : ProxyTrafficMetricsEvent

    data object ConnectionRejected : ProxyTrafficMetricsEvent

    data object ConnectionClosed : ProxyTrafficMetricsEvent

    data class BytesReceived(
        val bytes: Long,
    ) : ProxyTrafficMetricsEvent {
        init {
            require(bytes >= 0) { "Received byte delta must not be negative" }
        }
    }

    data class BytesSent(
        val bytes: Long,
    ) : ProxyTrafficMetricsEvent {
        init {
            require(bytes >= 0) { "Sent byte delta must not be negative" }
        }
    }
}

object ProxyTrafficMetricsReducer {
    fun apply(
        metrics: ProxyTrafficMetrics,
        event: ProxyTrafficMetricsEvent,
    ): ProxyTrafficMetrics = when (event) {
        ProxyTrafficMetricsEvent.ConnectionAccepted ->
            metrics.copy(
                activeConnections = metrics.activeConnections + 1,
                totalConnections = metrics.totalConnections + 1,
            )
        ProxyTrafficMetricsEvent.ConnectionRejected ->
            metrics.copy(
                rejectedConnections = metrics.rejectedConnections + 1,
            )
        ProxyTrafficMetricsEvent.ConnectionClosed -> {
            require(metrics.activeConnections > 0) {
                "Cannot close a proxy connection when no connections are active"
            }
            metrics.copy(activeConnections = metrics.activeConnections - 1)
        }
        is ProxyTrafficMetricsEvent.BytesReceived ->
            metrics.copy(
                bytesReceived = metrics.bytesReceived + event.bytes,
            )
        is ProxyTrafficMetricsEvent.BytesSent ->
            metrics.copy(
                bytesSent = metrics.bytesSent + event.bytes,
            )
    }
}
