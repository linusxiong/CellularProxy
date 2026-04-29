package com.cellularproxy.app.status

import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardRecentTrafficSamplerTest {
    @Test
    fun `first observation has no recent traffic window`() {
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { 1_000L },
            )

        assertNull(
            sampler.observe(
                ProxyTrafficMetrics(
                    totalConnections = 1,
                    bytesReceived = 100,
                    bytesSent = 200,
                ),
            ),
        )
    }

    @Test
    fun `second observation reports byte deltas over configured window`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200))
        now = 61_000L

        assertEquals(
            DashboardTrafficSummary(
                windowLabel = "Last 60 seconds",
                bytesReceived = 900,
                bytesSent = 1_800,
            ),
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000)),
        )
    }

    @Test
    fun `observations before the window elapses keep the previous summary`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200))
        now = 31_000L
        assertNull(sampler.observe(ProxyTrafficMetrics(bytesReceived = 400, bytesSent = 800)))

        now = 61_000L
        val firstSummary = sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000))
        now = 62_000L

        assertEquals(
            firstSummary,
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_100, bytesSent = 2_200)),
        )
    }

    @Test
    fun `summary label uses actual elapsed sample interval`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200))
        now = 301_000L

        assertEquals(
            DashboardTrafficSummary(
                windowLabel = "Last 300 seconds",
                bytesReceived = 900,
                bytesSent = 1_800,
            ),
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000)),
        )
    }

    @Test
    fun `summary label uses milliseconds for non whole-second intervals`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200))
        now = 61_500L

        assertEquals(
            DashboardTrafficSummary(
                windowLabel = "Last 60500 ms",
                bytesReceived = 900,
                bytesSent = 1_800,
            ),
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000)),
        )
    }

    @Test
    fun `repeated reads of last summary do not advance the sample baseline`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200))
        now = 61_000L
        sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000))
        now = 62_000L
        sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000))
        now = 121_000L

        assertEquals(
            DashboardTrafficSummary(
                windowLabel = "Last 60 seconds",
                bytesReceived = 500,
                bytesSent = 600,
            ),
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_500, bytesSent = 2_600)),
        )
    }

    @Test
    fun `counter reset clears the window instead of reporting negative traffic`() {
        var now = 1_000L
        val sampler =
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = { now },
            )

        sampler.observe(ProxyTrafficMetrics(bytesReceived = 1_000, bytesSent = 2_000))
        now = 61_000L

        assertNull(sampler.observe(ProxyTrafficMetrics(bytesReceived = 100, bytesSent = 200)))

        now = 121_000L
        assertEquals(
            DashboardTrafficSummary(
                windowLabel = "Last 60 seconds",
                bytesReceived = 300,
                bytesSent = 400,
            ),
            sampler.observe(ProxyTrafficMetrics(bytesReceived = 400, bytesSent = 600)),
        )
    }
}
