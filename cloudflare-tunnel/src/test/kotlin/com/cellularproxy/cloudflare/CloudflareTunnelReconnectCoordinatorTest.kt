package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelReconnectCoordinatorTest {
    @Test
    fun `successful degraded reconnect advances tunnel to connected`() {
        val controlPlane = degradedControlPlane()
        val degraded = controlPlane.snapshot()
        var receivedCredentials: CloudflareTunnelCredentials? = null
        val coordinator =
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector { credentials ->
                        receivedCredentials = credentials
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelReconnectCoordinatorResult.Applied>(
                coordinator.reconnectIfDegraded(
                    expectedSnapshot = degraded,
                    credentials = credentials(),
                ),
            )

        assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(result.connectionResult)
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.connected(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
        assertEquals("account-tag", receivedCredentials?.accountTag)
    }

    @Test
    fun `successful degraded reconnect replaces active session with committed connected snapshot`() {
        val controlPlane = degradedControlPlane()
        val degraded = controlPlane.snapshot()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val oldConnection = TrackableEdgeConnection()
        val newConnection = TrackableEdgeConnection()
        registry.install(degraded, oldConnection)
        val coordinator =
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Connected(newConnection)
                    },
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelReconnectCoordinatorResult.Applied>(
                coordinator.reconnectIfDegraded(degraded, credentials()),
            )

        assertSame(newConnection, registry.currentSessionOrNull()?.connection)
        assertEquals(result.transition.snapshot, registry.currentSessionOrNull()?.snapshot)
        assertEquals(CloudflareTunnelStatus.connected(), registry.currentSessionOrNull()?.snapshot?.status)
        assertTrue(oldConnection.closed)
    }

    @Test
    fun `failed degraded reconnect advances tunnel to failed with sanitized reason`() {
        CloudflareTunnelEdgeConnectionFailure.entries.forEach { failure ->
            val controlPlane = degradedControlPlane()
            val degraded = controlPlane.snapshot()
            val coordinator =
                CloudflareTunnelReconnectCoordinator(
                    controlPlane = controlPlane,
                    connector =
                        CloudflareTunnelEdgeConnector {
                            CloudflareTunnelEdgeConnectionResult.Failed(failure)
                        },
                )

            val result =
                assertIs<CloudflareTunnelReconnectCoordinatorResult.Applied>(
                    coordinator.reconnectIfDegraded(degraded, credentials()),
                )

            assertEquals(CloudflareTunnelEdgeConnectionResult.Failed(failure), result.connectionResult)
            assertEquals(CloudflareTunnelState.Failed, result.transition.status.state)
            assertEquals(failure.name, result.transition.status.failureReason)
            assertEquals(CloudflareTunnelState.Failed, controlPlane.currentStatus.state)
        }
    }

    @Test
    fun `stale reconnect snapshot is rejected before opening edge connection`() {
        val controlPlane = degradedControlPlane()
        val staleDegraded = controlPlane.snapshot()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        var connectorCalled = false
        val coordinator =
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        connectorCalled = true
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelReconnectCoordinatorResult.Stale>(
                coordinator.reconnectIfDegraded(staleDegraded, credentials()),
            )

        assertFalse(connectorCalled)
        assertEquals(staleDegraded, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.degraded(), controlPlane.currentStatus)
    }

    @Test
    fun `late successful reconnect is closed and rejected when tunnel state changes during reconnect`() {
        val controlPlane = degradedControlPlane()
        val degraded = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val coordinator =
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
                        CloudflareTunnelEdgeConnectionResult.Connected(connection)
                    },
            )

        val result =
            assertIs<CloudflareTunnelReconnectCoordinatorResult.Stale>(
                coordinator.reconnectIfDegraded(degraded, credentials()),
            )

        assertEquals(degraded, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        assertTrue(connection.closed)
    }

    @Test
    fun `non-degraded expected snapshot does not open edge connection`() {
        val controlPlane = CloudflareTunnelControlPlane(initialStatus = CloudflareTunnelStatus.connected())
        var connectorCalled = false
        val coordinator =
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        connectorCalled = true
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelReconnectCoordinatorResult.NoAction>(
                coordinator.reconnectIfDegraded(controlPlane.snapshot(), credentials()),
            )

        assertFalse(connectorCalled)
        assertEquals(CloudflareTunnelStatus.connected(), result.snapshot.status)
    }

    private fun degradedControlPlane(): CloudflareTunnelControlPlane {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        return controlPlane
    }

    private fun credentials(): CloudflareTunnelCredentials =
        CloudflareTunnelCredentials(
            accountTag = "account-tag",
            tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            tunnelSecret = byteArrayOf(1, 2, 3),
            endpoint = "edge.example.com",
        )

    private class TrackableEdgeConnection : CloudflareTunnelEdgeConnection {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }
}
