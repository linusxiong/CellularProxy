package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareTunnelStopCoordinatorTest {
    @Test
    fun `stop request closes connected edge connection and advances tunnel to stopped`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(connected, connection),
            )

        assertTrue(connection.closed)
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `registry-aware stop closes and clears matching active edge session`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        val coordinator =
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(connected),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `registry-aware stop closes connected session after tunnel becomes degraded`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        val degraded = controlPlane.snapshot()
        val coordinator =
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(degraded),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `registry-aware stop closes connected session after tunnel fails`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        controlPlane.apply(CloudflareTunnelEvent.Failed("ProtocolError"))
        val failed = controlPlane.snapshot()
        val coordinator =
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(failed),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `registry-aware stop closes connected session after failed reconnect`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        registry.install(connected, connection)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        CloudflareTunnelReconnectCoordinator(
            controlPlane = controlPlane,
            connector =
                CloudflareTunnelEdgeConnector {
                    CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.ProtocolError)
                },
            sessionRegistry = registry,
        ).reconnectIfDegraded(controlPlane.snapshot(), credentials())
        val failed = controlPlane.snapshot()
        val coordinator =
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(failed),
            )

        assertTrue(connection.closed)
        assertNull(registry.currentSessionOrNull())
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `registry-aware stale stop leaves active edge session untouched`() {
        val controlPlane = connectedControlPlane()
        val staleConnected = controlPlane.snapshot()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val connection = TrackableEdgeConnection()
        registry.install(controlPlane.snapshot(), connection)
        val coordinator =
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Stale>(
                coordinator.stopIfCurrent(staleConnected),
            )

        assertFalse(connection.closed)
        assertEquals(staleConnected, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(connection, registry.currentSessionOrNull()?.connection)
    }

    @Test
    fun `stop request can stop starting tunnel before edge connection exists`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val starting = controlPlane.apply(CloudflareTunnelEvent.StartRequested).snapshot
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(starting, activeConnection = null),
            )

        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `stop request can stop degraded tunnel`() {
        val controlPlane = connectedControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        val degraded = controlPlane.snapshot()
        val connection = TrackableEdgeConnection()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(degraded, connection),
            )

        assertTrue(connection.closed)
        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `stop request can stop failed tunnel`() {
        val controlPlane = connectedControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.Failed("ProtocolError"))
        val failed = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(failed, activeConnection = null),
            )

        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `stopped snapshot does not close provided connection`() {
        val controlPlane = connectedControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        val connection = TrackableEdgeConnection()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.NoAction>(
                coordinator.stopIfCurrent(controlPlane.snapshot(), connection),
            )

        assertFalse(connection.closed)
        assertEquals(CloudflareTunnelStatus.stopped(), result.snapshot.status)
    }

    @Test
    fun `stale stop snapshot is rejected without closing provided connection`() {
        val controlPlane = connectedControlPlane()
        val staleConnected = controlPlane.snapshot()
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val connection = TrackableEdgeConnection()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Stale>(
                coordinator.stopIfCurrent(staleConnected, connection),
            )

        assertFalse(connection.closed)
        assertEquals(staleConnected, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
    }

    @Test
    fun `stop commits lifecycle before closing active connection outside control-plane monitor`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val competingTransitionAttempted = CountDownLatch(1)
        val competingTransition = CountDownLatch(1)
        val statusWasStoppedDuringClose = AtomicBoolean(false)
        val competingTransitionCompletedDuringClose = AtomicBoolean(false)
        val connection =
            CloudflareTunnelEdgeConnection {
                statusWasStoppedDuringClose.set(controlPlane.currentStatus == CloudflareTunnelStatus.stopped())
                val competingThread =
                    Thread {
                        competingTransitionAttempted.countDown()
                        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
                        competingTransition.countDown()
                    }
                competingThread.start()
                assertTrue(competingTransitionAttempted.await(1, TimeUnit.SECONDS))
                competingTransitionCompletedDuringClose.set(
                    competingTransition.await(1, TimeUnit.SECONDS),
                )
            }
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(connected, connection),
            )

        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        assertTrue(statusWasStoppedDuringClose.get())
        assertTrue(competingTransitionCompletedDuringClose.get())
        assertTrue(competingTransition.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `ordinary edge connection close failure is suppressed and tunnel still stops`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.Applied>(
                coordinator.stopIfCurrent(
                    expectedSnapshot = connected,
                    activeConnection =
                        CloudflareTunnelEdgeConnection {
                            throw java.io.IOException("raw close failure tunnel-secret")
                        },
                ),
            )

        assertEquals(CloudflareTunnelStatus.stopped(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `edge close interruption is rethrown with interrupt flag restored after stop commits`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        try {
            assertFailsWith<InterruptedException> {
                coordinator.stopIfCurrent(
                    expectedSnapshot = connected,
                    activeConnection =
                        CloudflareTunnelEdgeConnection {
                            throw InterruptedException("shutdown")
                        },
                )
            }

            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `edge close cancellation is rethrown after stop commits`() {
        val controlPlane = connectedControlPlane()
        val connected = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        assertFailsWith<CancellationException> {
            coordinator.stopIfCurrent(
                expectedSnapshot = connected,
                activeConnection =
                    CloudflareTunnelEdgeConnection {
                        throw CancellationException("cancelled")
                    },
            )
        }

        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `inactive snapshot does not close provided connection`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val connection = TrackableEdgeConnection()
        val coordinator = CloudflareTunnelStopCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStopCoordinatorResult.NoAction>(
                coordinator.stopIfCurrent(controlPlane.snapshot(), connection),
            )

        assertFalse(connection.closed)
        assertEquals(CloudflareTunnelStatus.disabled(), result.snapshot.status)
    }

    @Test
    fun `applied stop result rejects non-accepted transition`() {
        val transition =
            CloudflareTunnelControlPlane()
                .apply(CloudflareTunnelEvent.StopRequested)

        val failure =
            kotlin.runCatching {
                CloudflareTunnelStopCoordinatorResult.Applied(transition)
            }

        assertTrue(failure.isFailure)
    }

    private fun connectedControlPlane(): CloudflareTunnelControlPlane {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        return controlPlane
    }

    private fun credentials(): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
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
