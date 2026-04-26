package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelDegradeCoordinatorTest {
    @Test
    fun `current connected edge session can mark tunnel degraded`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        val coordinator = CloudflareTunnelDegradeCoordinator(
            controlPlane = controlPlane,
            sessionRegistry = registry,
        )

        val result = assertIs<CloudflareTunnelDegradeCoordinatorResult.Applied>(
            coordinator.markDegradedIfCurrent(
                expectedSnapshot = connected,
                activeConnection = connection,
            ),
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.degraded(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.degraded(), controlPlane.currentStatus)
        assertSame(connection, registry.currentSessionOrNull()?.connection)
        assertEquals(result.transition.snapshot, registry.currentSessionOrNull()?.snapshot)
        assertFalse(connection.closed)
    }

    @Test
    fun `stale degraded report is rejected before mutating lifecycle`() {
        val controlPlane = connectedControlPlane()
        val staleConnected = controlPlane.snapshot()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        val currentConnected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(currentConnected, connection)
        val coordinator = CloudflareTunnelDegradeCoordinator(
            controlPlane = controlPlane,
            sessionRegistry = registry,
        )

        val result = assertIs<CloudflareTunnelDegradeCoordinatorResult.Stale>(
            coordinator.markDegradedIfCurrent(staleConnected, connection),
        )

        assertEquals(staleConnected, result.expectedSnapshot)
        assertEquals(currentConnected, result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
        assertSame(connection, registry.currentSessionOrNull()?.connection)
    }

    @Test
    fun `degraded report from replaced edge session is rejected`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val staleConnection = TrackableEdgeConnection()
        val currentConnection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, currentConnection)
        val coordinator = CloudflareTunnelDegradeCoordinator(
            controlPlane = controlPlane,
            sessionRegistry = registry,
        )

        val result = assertIs<CloudflareTunnelDegradeCoordinatorResult.ActiveSessionMismatch>(
            coordinator.markDegradedIfCurrent(
                expectedSnapshot = connected,
                activeConnection = staleConnection,
            ),
        )

        assertEquals(connected, result.snapshot)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
        assertSame(currentConnection, registry.currentSessionOrNull()?.connection)
        assertFalse(staleConnection.closed)
    }

    @Test
    fun `non-connected expected snapshot does not mark tunnel degraded`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val coordinator = CloudflareTunnelDegradeCoordinator(
            controlPlane = controlPlane,
            sessionRegistry = registry,
        )

        val result = assertIs<CloudflareTunnelDegradeCoordinatorResult.NoAction>(
            coordinator.markDegradedIfCurrent(controlPlane.snapshot(), connection),
        )

        assertEquals(CloudflareTunnelStatus.disabled(), result.snapshot.status)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
        assertFalse(connection.closed)
    }

    @Test
    fun `current session query validates both snapshot and connection identity`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val otherConnection = TrackableEdgeConnection()
        registry.install(connected, connection)

        assertTrue(registry.isCurrent(connected, connection))
        assertFalse(registry.isCurrent(connected, otherConnection))
        assertFalse(registry.isCurrent(controlPlane.apply(CloudflareTunnelEvent.Degraded).snapshot, connection))
    }

    @Test
    fun `current session snapshot update preserves active connection identity`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val degraded = controlPlane.apply(CloudflareTunnelEvent.Degraded).snapshot
        val connection = TrackableEdgeConnection()
        val otherConnection = TrackableEdgeConnection()
        registry.install(connected, connection)

        assertFalse(registry.updateSnapshotIfCurrent(connected, degraded, otherConnection))
        assertTrue(registry.updateSnapshotIfCurrent(connected, degraded, connection))

        assertSame(connection, registry.currentSessionOrNull()?.connection)
        assertEquals(degraded, registry.currentSessionOrNull()?.snapshot)
        assertFalse(connection.closed)
    }

    @Test
    fun `applied degraded result rejects non-accepted transition`() {
        val transition = CloudflareTunnelControlPlane()
            .apply(CloudflareTunnelEvent.Degraded)

        val failure = kotlin.runCatching {
            CloudflareTunnelDegradeCoordinatorResult.Applied(transition)
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
