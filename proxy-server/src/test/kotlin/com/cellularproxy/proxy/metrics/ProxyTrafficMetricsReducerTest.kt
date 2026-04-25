package com.cellularproxy.proxy.metrics

import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProxyTrafficMetricsReducerTest {
    @Test
    fun `applies accepted rejected closed and byte events to traffic metrics`() {
        val metrics = listOf(
            ProxyTrafficMetricsEvent.ConnectionAccepted,
            ProxyTrafficMetricsEvent.ConnectionAccepted,
            ProxyTrafficMetricsEvent.BytesReceived(120),
            ProxyTrafficMetricsEvent.BytesSent(75),
            ProxyTrafficMetricsEvent.ConnectionRejected,
            ProxyTrafficMetricsEvent.ConnectionClosed,
        ).fold(ProxyTrafficMetrics(), ProxyTrafficMetricsReducer::apply)

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 1,
                totalConnections = 2,
                rejectedConnections = 1,
                bytesReceived = 120,
                bytesSent = 75,
            ),
            metrics,
        )
    }

    @Test
    fun `rejects closing a connection when no connections are active`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetricsReducer.apply(
                metrics = ProxyTrafficMetrics(),
                event = ProxyTrafficMetricsEvent.ConnectionClosed,
            )
        }
    }

    @Test
    fun `rejects negative byte deltas`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetricsEvent.BytesReceived(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetricsEvent.BytesSent(-1)
        }
    }
}
