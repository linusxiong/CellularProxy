package com.cellularproxy.shared.cloudflare

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudflareTunnelControlPlaneTest {
    @Test
    fun `starts disabled tunnel and records accepted transition generation`() {
        val controlPlane = CloudflareTunnelControlPlane()

        val result = controlPlane.apply(CloudflareTunnelEvent.StartRequested)

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), result.status)
        val expectedSnapshot =
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.starting(),
                transitionGeneration = 1,
            )
        assertEquals(expectedSnapshot, result.snapshot)
        assertEquals(expectedSnapshot, controlPlane.snapshot())
    }

    @Test
    fun `duplicate lifecycle events do not mutate status or generation`() {
        val controlPlane = CloudflareTunnelControlPlane(initialStatus = CloudflareTunnelStatus.starting())

        val result = controlPlane.apply(CloudflareTunnelEvent.StartRequested)

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, result.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), result.status)
        assertEquals(
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.starting(),
                transitionGeneration = 0,
            ),
            controlPlane.snapshot(),
        )
    }

    @Test
    fun `ignored stale lifecycle events do not mutate status or generation`() {
        val controlPlane = CloudflareTunnelControlPlane()

        val result = controlPlane.apply(CloudflareTunnelEvent.Connected)

        assertEquals(CloudflareTunnelTransitionDisposition.Ignored, result.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), result.status)
        assertEquals(
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.disabled(),
                transitionGeneration = 0,
            ),
            controlPlane.snapshot(),
        )
    }

    @Test
    fun `accepted lifecycle progress mutates status and increments generation`() {
        val controlPlane = CloudflareTunnelControlPlane()

        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val connected = controlPlane.apply(CloudflareTunnelEvent.Connected)
        val degraded = controlPlane.apply(CloudflareTunnelEvent.Degraded)

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, connected.disposition)
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, degraded.disposition)
        assertEquals(
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.degraded(),
                transitionGeneration = 3,
            ),
            controlPlane.snapshot(),
        )
    }

    @Test
    fun `guarded lifecycle event rejects stale callback after same state is reached again`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val firstStart = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.StopRequested)
        val secondStart = controlPlane.apply(CloudflareTunnelEvent.StartRequested)

        val staleResult =
            controlPlane.apply(
                expectedSnapshot = firstStart.snapshot,
                event = CloudflareTunnelEvent.Connected,
            )

        val stale = staleResult as CloudflareTunnelGuardedTransitionResult.Stale
        assertEquals(firstStart.snapshot, stale.expectedSnapshot)
        assertEquals(secondStart.snapshot, stale.actualSnapshot)
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
        assertEquals(secondStart.snapshot, controlPlane.snapshot())
    }

    @Test
    fun `guarded lifecycle event applies when expected snapshot still matches`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val started = controlPlane.apply(CloudflareTunnelEvent.StartRequested)

        val result =
            controlPlane.apply(
                expectedSnapshot = started.snapshot,
                event = CloudflareTunnelEvent.Connected,
            )

        val evaluated = result as CloudflareTunnelGuardedTransitionResult.Evaluated
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, evaluated.transition.disposition)
        assertEquals(
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.connected(),
                transitionGeneration = 2,
            ),
            evaluated.transition.snapshot,
        )
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
    }

    @Test
    fun `concurrent start requests commit only one accepted transition`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val results =
                MutableList(workers) {
                    executor.submit<CloudflareTunnelControlPlaneTransitionResult> {
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
                    }
                }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val transitions = results.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, transitions.count { it.disposition == CloudflareTunnelTransitionDisposition.Accepted })
            assertEquals(15, transitions.count { it.disposition == CloudflareTunnelTransitionDisposition.Duplicate })
            assertTrue(transitions.all { it.status == CloudflareTunnelStatus.starting() })
            assertEquals(
                CloudflareTunnelControlPlaneSnapshot(
                    status = CloudflareTunnelStatus.starting(),
                    transitionGeneration = 1,
                ),
                controlPlane.snapshot(),
            )
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `snapshot rejects negative generation`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelControlPlaneSnapshot(
                status = CloudflareTunnelStatus.disabled(),
                transitionGeneration = -1,
            )
        }
    }
}
