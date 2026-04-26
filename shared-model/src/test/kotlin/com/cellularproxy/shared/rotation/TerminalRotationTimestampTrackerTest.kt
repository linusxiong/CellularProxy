package com.cellularproxy.shared.rotation

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalRotationTimestampTrackerTest {
    @Test
    fun `starts with optional initial last terminal timestamp exposed read only`() {
        val emptyTracker = TerminalRotationTimestampTracker()
        val seededTracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 12_345)

        assertNull(emptyTracker.lastTerminalElapsedMillis)
        assertEquals(12_345, seededTracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `rejects negative initial and observation timestamps`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = -1)
        }

        val tracker = TerminalRotationTimestampTracker()

        assertFailsWith<IllegalArgumentException> {
            tracker.observe(
                transition = acceptedCompletedTransition(),
                nowElapsedMillis = -1,
            )
        }
    }

    @Test
    fun `records accepted completed rotation timestamp`() {
        val tracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 1_000)

        val decision = tracker.observe(
            transition = acceptedCompletedTransition(),
            nowElapsedMillis = 2_500,
        )

        assertEquals(TerminalRotationTimestampObservation.Recorded(2_500), decision)
        assertEquals(2_500, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `records accepted failed attempted rotation timestamp`() {
        val tracker = TerminalRotationTimestampTracker()

        val decision = tracker.observe(
            transition = acceptedFailedTransition(RotationFailureReason.RootUnavailable),
            nowElapsedMillis = 3_000,
        )

        assertEquals(TerminalRotationTimestampObservation.Recorded(3_000), decision)
        assertEquals(3_000, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `does not record cooldown rejection failure`() {
        val tracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 1_000)

        val decision = tracker.observe(
            transition = acceptedFailedTransition(RotationFailureReason.CooldownActive),
            nowElapsedMillis = 2_000,
        )

        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.CooldownRejected,
            ),
            decision,
        )
        assertEquals(1_000, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `does not record duplicate or ignored transitions`() {
        val tracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 1_000)

        val duplicateDecision = tracker.observe(
            transition = RotationTransitionResult(
                disposition = RotationTransitionDisposition.Duplicate,
                status = completedStatus(),
            ),
            nowElapsedMillis = 2_000,
        )
        val ignoredDecision = tracker.observe(
            transition = RotationTransitionResult(
                disposition = RotationTransitionDisposition.Ignored,
                status = completedStatus(),
            ),
            nowElapsedMillis = 3_000,
        )

        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
            ),
            duplicateDecision,
        )
        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
            ),
            ignoredDecision,
        )
        assertEquals(1_000, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `does not record accepted non terminal transition`() {
        val tracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 1_000)

        val decision = tracker.observe(
            transition = RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(
                    state = RotationState.CheckingRoot,
                    operation = RotationOperation.MobileData,
                ),
            ),
            nowElapsedMillis = 2_000,
        )

        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.NotTerminal,
            ),
            decision,
        )
        assertEquals(1_000, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `does not move last terminal timestamp backwards`() {
        val tracker = TerminalRotationTimestampTracker(initialLastTerminalElapsedMillis = 10_000)

        val decision = tracker.observe(
            transition = acceptedCompletedTransition(),
            nowElapsedMillis = 5_000,
        )

        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.StaleTerminalTimestamp,
            ),
            decision,
        )
        assertEquals(10_000, tracker.lastTerminalElapsedMillis)
    }

    @Test
    fun `concurrent terminal observations preserve newest timestamp`() {
        val tracker = TerminalRotationTimestampTracker()
        val contenderCount = 8
        val executor = Executors.newFixedThreadPool(contenderCount)
        val ready = CountDownLatch(contenderCount)
        val start = CountDownLatch(1)

        try {
            val futures = List(contenderCount) { index ->
                executor.submit(Callable {
                    ready.countDown()
                    start.await()
                    tracker.observe(
                        transition = acceptedCompletedTransition(),
                        nowElapsedMillis = (index + 1) * 1_000L,
                    )
                })
            }

            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(1, TimeUnit.SECONDS) }

            assertEquals(contenderCount * 1_000L, tracker.lastTerminalElapsedMillis)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private fun acceptedCompletedTransition(): RotationTransitionResult =
        RotationTransitionResult(
            disposition = RotationTransitionDisposition.Accepted,
            status = completedStatus(),
        )

    private fun acceptedFailedTransition(reason: RotationFailureReason): RotationTransitionResult =
        RotationTransitionResult(
            disposition = RotationTransitionDisposition.Accepted,
            status = RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.MobileData,
                failureReason = reason,
            ),
        )

    private fun completedStatus(): RotationStatus =
        RotationStatus(
            state = RotationState.Completed,
            operation = RotationOperation.MobileData,
            oldPublicIp = "198.51.100.10",
            newPublicIp = "198.51.100.11",
            publicIpChanged = true,
        )
}
