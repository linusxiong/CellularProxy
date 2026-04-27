package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelConnectionCoordinatorTest {
    @Test
    fun `successful edge connection advances starting tunnel to connected`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        var receivedCredentials: CloudflareTunnelCredentials? = null
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector { credentials ->
                        receivedCredentials = credentials
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
                coordinator.connectIfStarting(
                    expectedSnapshot = started.snapshot,
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
    fun `successful edge connection installs active session for committed connected snapshot`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val connection = TrackableEdgeConnection()
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Connected(connection)
                    },
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
                coordinator.connectIfStarting(started.snapshot, credentials()),
            )

        assertSame(connection, registry.currentSessionOrNull()?.connection)
        assertEquals(result.transition.snapshot, registry.currentSessionOrNull()?.snapshot)
        assertEquals(CloudflareTunnelStatus.connected(), registry.currentSessionOrNull()?.snapshot?.status)
    }

    @Test
    fun `stop cannot interleave between connected transition and active session install`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val registry = BlockingInstallSessionRegistry()
        val connection = TrackableEdgeConnection()
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Connected(connection)
                    },
                sessionRegistry = registry,
            )
        val connectResult = AtomicReference<CloudflareTunnelConnectionCoordinatorResult.Applied>()
        val connectFailure = AtomicReference<Throwable>()
        val connectFinished = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val connectThread =
            Thread {
                try {
                    connectResult.set(
                        assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
                            coordinator.connectIfStarting(started.snapshot, credentials()),
                        ),
                    )
                } catch (throwable: Throwable) {
                    connectFailure.set(throwable)
                } finally {
                    connectFinished.countDown()
                }
            }
        val stopThread =
            Thread {
                registry.installReached.await(1, TimeUnit.SECONDS)
                stopStarted.countDown()
                CloudflareTunnelStopCoordinator(
                    controlPlane = controlPlane,
                    sessionRegistry = registry,
                ).stopIfCurrent(controlPlane.snapshot())
                stopFinished.countDown()
            }

        connectThread.start()
        stopThread.start()
        assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
        assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))
        registry.allowInstall.countDown()
        assertTrue(connectFinished.await(1, TimeUnit.SECONDS))
        assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
        connectThread.join(1_000)
        stopThread.join(1_000)
        connectFailure.get()?.let { throw it }

        assertEquals(CloudflareTunnelStatus.connected(), connectResult.get().transition.status)
        assertTrue(connection.closed)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        assertEquals(null, registry.currentSessionOrNull())
    }

    @Test
    fun `failed edge connection does not install active session`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable)
                    },
                sessionRegistry = registry,
            )

        assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
            coordinator.connectIfStarting(started.snapshot, credentials()),
        )

        assertEquals(null, registry.currentSessionOrNull())
    }

    @Test
    fun `failed edge connection advances starting tunnel to failed without raw exception text`() {
        CloudflareTunnelEdgeConnectionFailure.entries.forEach { failure ->
            val controlPlane = CloudflareTunnelControlPlane()
            val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
            val coordinator =
                CloudflareTunnelConnectionCoordinator(
                    controlPlane = controlPlane,
                    connector =
                        CloudflareTunnelEdgeConnector {
                            CloudflareTunnelEdgeConnectionResult.Failed(failure)
                        },
                )

            val result =
                assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
                    coordinator.connectIfStarting(started.snapshot, credentials()),
                )

            assertEquals(CloudflareTunnelEdgeConnectionResult.Failed(failure), result.connectionResult)
            assertEquals(CloudflareTunnelState.Failed, result.transition.status.state)
            assertEquals(failure.name, result.transition.status.failureReason)
            assertEquals(CloudflareTunnelState.Failed, controlPlane.currentStatus.state)
        }
    }

    @Test
    fun `stale snapshot is rejected before opening edge connection`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val firstStart = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        var connectorCalled = false
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        connectorCalled = true
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.Stale>(
                coordinator.connectIfStarting(firstStart.snapshot, credentials()),
            )

        assertFalse(connectorCalled)
        assertEquals(firstStart.snapshot, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
    }

    @Test
    fun `late edge connection result is closed and rejected when tunnel state changes during connection`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val connection = TrackableEdgeConnection()
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
                        CloudflareTunnelEdgeConnectionResult.Connected(connection)
                    },
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.Stale>(
                coordinator.connectIfStarting(started.snapshot, credentials()),
            )

        assertEquals(started.snapshot, result.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), result.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        assertTrue(connection.closed)
    }

    @Test
    fun `late edge connection close interruption is rethrown with interrupt flag restored`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
                        CloudflareTunnelEdgeConnectionResult.Connected(
                            CloudflareTunnelEdgeConnection {
                                throw InterruptedException("shutdown")
                            },
                        )
                    },
            )

        try {
            assertFailsWith<InterruptedException> {
                coordinator.connectIfStarting(started.snapshot, credentials())
            }

            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `late edge connection close cancellation is rethrown`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
                        CloudflareTunnelEdgeConnectionResult.Connected(
                            CloudflareTunnelEdgeConnection {
                                throw CancellationException("cancelled")
                            },
                        )
                    },
            )

        assertFailsWith<CancellationException> {
            coordinator.connectIfStarting(started.snapshot, credentials())
        }
        assertEquals(CloudflareTunnelStatus.stopped(), controlPlane.currentStatus)
    }

    @Test
    fun `connector exceptions become sanitized failed tunnel transitions`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        throw java.io.IOException("raw edge token tunnel-secret")
                    },
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(
                coordinator.connectIfStarting(started.snapshot, credentials()),
            )

        assertEquals(
            CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.ProtocolError),
            result.connectionResult,
        )
        assertEquals(CloudflareTunnelStatus.failed("ProtocolError"), result.transition.status)
        assertFalse(
            result.transition.status.failureReason
                .orEmpty()
                .contains("tunnel-secret"),
        )
    }

    @Test
    fun `connector interruption is rethrown with interrupt flag restored`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        throw InterruptedException("shutdown")
                    },
            )

        try {
            assertFailsWith<InterruptedException> {
                coordinator.connectIfStarting(started.snapshot, credentials())
            }

            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `connector cancellation is rethrown without mutating tunnel state`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        throw CancellationException("cancelled")
                    },
            )

        assertFailsWith<CancellationException> {
            coordinator.connectIfStarting(started.snapshot, credentials())
        }
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
    }

    @Test
    fun `non-starting expected snapshot does not open edge connection`() {
        val controlPlane = CloudflareTunnelControlPlane()
        var connectorCalled = false
        val coordinator =
            CloudflareTunnelConnectionCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        connectorCalled = true
                        CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                    },
            )

        val result =
            assertIs<CloudflareTunnelConnectionCoordinatorResult.NoAction>(
                coordinator.connectIfStarting(controlPlane.snapshot(), credentials()),
            )

        assertFalse(connectorCalled)
        assertEquals(CloudflareTunnelStatus.disabled(), result.snapshot.status)
    }

    @Test
    fun `applied result rejects non-accepted transition`() {
        val transition =
            CloudflareTunnelControlPlane()
                .apply(CloudflareTunnelEvent.Connected)

        val failure =
            kotlin.runCatching {
                CloudflareTunnelConnectionCoordinatorResult.Applied(
                    connectionResult = CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection()),
                    transition = transition,
                )
            }

        assertTrue(failure.isFailure)
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

    private class BlockingInstallSessionRegistry : CloudflareTunnelEdgeSessionStore {
        val installReached = CountDownLatch(1)
        val allowInstall = CountDownLatch(1)
        private val delegate = CloudflareTunnelEdgeSessionRegistry()

        fun currentSessionOrNull(): CloudflareTunnelEdgeSession? = delegate.currentSessionOrNull()

        override fun install(
            snapshot: CloudflareTunnelControlPlaneSnapshot,
            connection: CloudflareTunnelEdgeConnection,
        ): CloudflareTunnelEdgeSessionInstallResult {
            installReached.countDown()
            assertTrue(allowInstall.await(1, TimeUnit.SECONDS))
            return delegate.install(snapshot, connection)
        }

        override fun takeActiveConnection(): CloudflareTunnelEdgeConnection? = delegate.takeActiveConnection()
    }
}
