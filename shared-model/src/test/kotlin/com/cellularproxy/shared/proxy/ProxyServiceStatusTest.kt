package com.cellularproxy.shared.proxy

import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyServiceStatusTest {
    @Test
    fun `default status represents a stopped proxy with safe empty metrics`() {
        val status = ProxyServiceStatus.stopped()

        assertEquals(ProxyServiceState.Stopped, status.state)
        assertNull(status.listenHost)
        assertNull(status.listenPort)
        assertEquals(RouteTarget.Automatic, status.configuredRoute)
        assertNull(status.boundRoute)
        assertNull(status.publicIp)
        assertFalse(status.hasHighSecurityRisk)
        assertEquals(CloudflareTunnelState.Disabled, status.cloudflare.state)
        assertEquals(RootAvailabilityStatus.Unknown, status.rootAvailability)
        assertEquals(ProxyTrafficMetrics(), status.metrics)
        assertNull(status.startupError)
    }

    @Test
    fun `running status aggregates listener route security cloudflare root and metrics`() {
        val cellularRoute =
            NetworkDescriptor(
                id = "net-42",
                category = NetworkCategory.Cellular,
                displayName = "Carrier LTE",
                isAvailable = true,
            )
        val metrics =
            ProxyTrafficMetrics(
                activeConnections = 2,
                totalConnections = 5,
                rejectedConnections = 1,
                bytesReceived = 1234,
                bytesSent = 5678,
            )

        val status =
            ProxyServiceStatus.running(
                listenHost = "0.0.0.0",
                listenPort = 8080,
                configuredRoute = RouteTarget.Cellular,
                boundRoute = cellularRoute,
                publicIp = "203.0.113.10",
                hasHighSecurityRisk = true,
                cloudflare = CloudflareTunnelStatus.connected(),
                rootAvailability = RootAvailabilityStatus.Available,
                metrics = metrics,
            )

        assertEquals(ProxyServiceState.Running, status.state)
        assertEquals("0.0.0.0", status.listenHost)
        assertEquals(8080, status.listenPort)
        assertEquals(RouteTarget.Cellular, status.configuredRoute)
        assertEquals(cellularRoute, status.boundRoute)
        assertEquals("203.0.113.10", status.publicIp)
        assertTrue(status.hasHighSecurityRisk)
        assertEquals(CloudflareTunnelState.Connected, status.cloudflare.state)
        assertEquals(RootAvailabilityStatus.Available, status.rootAvailability)
        assertEquals(metrics, status.metrics)
        assertNull(status.startupError)
    }

    @Test
    fun `traffic metrics reject negative counters`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(activeConnections = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(totalConnections = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(rejectedConnections = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(bytesReceived = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(bytesSent = -1)
        }
    }

    @Test
    fun `traffic metrics reject active connections greater than total connections`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyTrafficMetrics(activeConnections = 3, totalConnections = 2)
        }
    }

    @Test
    fun `inactive status rejects active proxy connections`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyServiceStatus.stopped(
                metrics = ProxyTrafficMetrics(activeConnections = 1, totalConnections = 1),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ProxyServiceStatus.failed(
                startupError = ProxyStartupError.PortAlreadyInUse,
                metrics = ProxyTrafficMetrics(activeConnections = 1, totalConnections = 1),
            )
        }
    }

    @Test
    fun `failed startup status requires and exposes structured startup error`() {
        val status = ProxyServiceStatus.failed(ProxyStartupError.MissingManagementApiToken)

        assertEquals(ProxyServiceState.Failed, status.state)
        assertEquals(ProxyStartupError.MissingManagementApiToken, status.startupError)
        assertFailsWith<IllegalArgumentException> {
            ProxyServiceStatus(
                state = ProxyServiceState.Failed,
                startupError = null,
            )
        }
    }

    @Test
    fun `non failed statuses reject stale startup errors`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyServiceStatus(
                state = ProxyServiceState.Running,
                listenHost = "127.0.0.1",
                listenPort = 8080,
                boundRoute =
                    NetworkDescriptor(
                        id = "wifi",
                        category = NetworkCategory.WiFi,
                        displayName = "Wi-Fi",
                        isAvailable = true,
                    ),
                startupError = ProxyStartupError.UnavailableSelectedRoute,
            )
        }
    }
}
