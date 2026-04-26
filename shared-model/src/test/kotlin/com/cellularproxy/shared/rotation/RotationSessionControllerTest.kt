package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotationSessionControllerTest {
    @Test
    fun `request start mutates idle session into cooldown check`() {
        val controller = RotationSessionController()

        val result = controller.requestStart(RotationOperation.MobileData)

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.CheckingCooldown, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals(result.status, controller.currentStatus)
    }

    @Test
    fun `duplicate start while active returns duplicate and leaves active status unchanged`() {
        val controller = RotationSessionController()
        val active = controller.requestStart(RotationOperation.AirplaneMode).status

        val duplicate = controller.requestStart(RotationOperation.MobileData)

        assertEquals(RotationTransitionDisposition.Duplicate, duplicate.disposition)
        assertEquals(active, duplicate.status)
        assertEquals(active, controller.currentStatus)
    }

    @Test
    fun `only one concurrent start request is accepted`() {
        val controller = RotationSessionController()
        val contenderCount = 8
        val executor = Executors.newFixedThreadPool(contenderCount)
        val ready = CountDownLatch(contenderCount)
        val start = CountDownLatch(1)

        try {
            val futures = List(contenderCount) { index ->
                executor.submit(Callable {
                    ready.countDown()
                    start.await()
                    controller.requestStart(
                        if (index % 2 == 0) {
                            RotationOperation.MobileData
                        } else {
                            RotationOperation.AirplaneMode
                        },
                    )
                })
            }

            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map { it.get(1, TimeUnit.SECONDS) }
            val accepted = results.single { it.disposition == RotationTransitionDisposition.Accepted }
            val duplicates = results.filter { it.disposition == RotationTransitionDisposition.Duplicate }

            assertEquals(1, results.count { it.disposition == RotationTransitionDisposition.Accepted })
            assertEquals(contenderCount - 1, duplicates.size)
            assertEquals(RotationState.CheckingCooldown, controller.currentStatus.state)
            assertEquals(accepted.status, controller.currentStatus)
            assertTrue(duplicates.all { it.status == accepted.status })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `accepted event mutates the current status`() {
        val controller = RotationSessionController()
        controller.requestStart(RotationOperation.MobileData)

        val result = controller.apply(RotationEvent.CooldownPassed)

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.CheckingRoot, result.status.state)
        assertEquals(result.status, controller.currentStatus)
    }

    @Test
    fun `ignored event returns current status without mutating session`() {
        val controller = RotationSessionController()
        val active = controller.requestStart(RotationOperation.MobileData).status

        val ignored = controller.apply(RotationEvent.RootAvailable)

        assertEquals(RotationTransitionDisposition.Ignored, ignored.disposition)
        assertEquals(active, ignored.status)
        assertEquals(active, controller.currentStatus)
    }

    @Test
    fun `seeded session continues transitions from provided status`() {
        val runningDisable = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
        )
        val controller = RotationSessionController(initialStatus = runningDisable)

        val result = controller.apply(
            RotationEvent.RootCommandCompleted(
                RootCommandResult.completed(
                    category = RootCommandCategory.MobileDataDisable,
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                ),
            ),
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.WaitingForToggleDelay, result.status.state)
        assertEquals(result.status, controller.currentStatus)
    }

    private fun statusAfter(vararg events: RotationEvent): RotationStatus =
        events.fold(RotationStatus.idle()) { status, event ->
            val result = RotationStateMachine.transition(status, event)
            assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
            result.status
        }
}
