package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareTunnelDisableCoordinatorTest {
    @Test
    fun `disable request clears and closes connected edge session while moving tunnel to disabled`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        val coordinator =
            CloudflareTunnelDisableCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelDisableCoordinatorResult.Applied>(
                coordinator.disableIfCurrent(connected),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `disable request can disable starting tunnel before edge connection exists`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val starting = controlPlane.apply(CloudflareTunnelEvent.StartRequested).snapshot
        val coordinator = CloudflareTunnelDisableCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelDisableCoordinatorResult.Applied>(
                coordinator.disableIfCurrent(starting),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `disable request closes connected session after tunnel is degraded`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        val degraded = controlPlane.snapshot()
        val coordinator =
            CloudflareTunnelDisableCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelDisableCoordinatorResult.Applied>(
                coordinator.disableIfCurrent(degraded),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelStatus.disabled(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `stale disable snapshot is rejected without closing current active edge session`() {
        val controlPlane = connectedControlPlane()
        val staleConnected = controlPlane.snapshot()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        val currentConnected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(currentConnected, connection)
        val coordinator =
            CloudflareTunnelDisableCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelDisableCoordinatorResult.Stale>(
                coordinator.disableIfCurrent(staleConnected),
            )

        assertFalse(connection.closed)
        assertEquals(staleConnected, result.expectedSnapshot)
        assertEquals(currentConnected, result.actualSnapshot)
        assertEquals(connection, registry.currentSessionOrNull()?.connection)
    }

    @Test
    fun `already disabled tunnel reports duplicate transition without requiring active session`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val disabled = controlPlane.snapshot()
        val coordinator = CloudflareTunnelDisableCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelDisableCoordinatorResult.Applied>(
                coordinator.disableIfCurrent(disabled),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), result.transition.status)
        assertEquals(disabled, result.transition.snapshot)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `applied disable result rejects non-disabled transition`() {
        val transition =
            CloudflareTunnelControlPlane()
                .apply(CloudflareTunnelEvent.StartRequested)

        val failure =
            kotlin.runCatching {
                CloudflareTunnelDisableCoordinatorResult.Applied(transition)
            }

        assertTrue(failure.isFailure)
    }

    private fun connectedControlPlane(): CloudflareTunnelControlPlane {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        return controlPlane
    }

    private class TrackableEdgeConnection : CloudflareTunnelEdgeConnection {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }
}
