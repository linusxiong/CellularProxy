package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RotationToggleDelayCoordinatorTest {
    @Test
    fun `elapsed toggle delay advances control plane to enable command`() {
        val controlPlane = waitingForToggleDelayControlPlane()
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        val result = coordinator.advance(
            expectedSnapshot = controlPlane.snapshot(),
            delayStartedElapsedMillis = 10_000,
            nowElapsedMillis = 13_000,
            toggleDelay = 3.seconds,
        )

        val applied = result as RotationToggleDelayAdvanceResult.Applied
        assertEquals(RotationToggleDelayDecision.Elapsed, applied.decision)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.RunningEnableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `waiting toggle delay leaves control plane waiting for toggle delay`() {
        val controlPlane = waitingForToggleDelayControlPlane()
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        val result = coordinator.advance(
            expectedSnapshot = controlPlane.snapshot(),
            delayStartedElapsedMillis = 10_000,
            nowElapsedMillis = 11_000,
            toggleDelay = 3.seconds,
        )

        val waiting = result as RotationToggleDelayAdvanceResult.Waiting
        assertEquals(
            RotationToggleDelayDecision.Waiting(remainingDelay = 2.seconds),
            waiting.decision,
        )
        assertEquals(RotationState.WaitingForToggleDelay, waiting.snapshot.status.state)
        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale toggle delay observation does not advance a newer waiting phase`() {
        val currentStatus = RotationStatus(
            state = RotationState.WaitingForToggleDelay,
            operation = RotationOperation.MobileData,
            oldPublicIp = "198.51.100.10",
        )
        val staleSnapshot = RotationControlPlaneSnapshot(
            status = currentStatus,
            lastTerminalElapsedMillis = 1_000,
        )
        val controlPlane = RotationControlPlane(
            initialStatus = currentStatus,
            initialLastTerminalElapsedMillis = 20_000,
        )
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        val result = coordinator.advance(
            expectedSnapshot = staleSnapshot,
            delayStartedElapsedMillis = 10_000,
            nowElapsedMillis = 13_000,
            toggleDelay = 3.seconds,
        )

        val stale = result as RotationToggleDelayAdvanceResult.Stale
        assertEquals(staleSnapshot, stale.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), stale.actualSnapshot)
        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale toggle delay observation is rejected when values match but generation differs`() {
        val controlPlane = RotationControlPlane(
            initialStatus = RotationStatus(
                state = RotationState.RunningDisableCommand,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
            ),
        )
        controlPlane.applyProgress(
            event = RotationEvent.RootCommandCompleted(
                result = com.cellularproxy.shared.root.RootCommandResult.completed(
                    category = com.cellularproxy.shared.root.RootCommandCategory.MobileDataDisable,
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                ),
            ),
            nowElapsedMillis = 10_000,
        )
        val actualSnapshot = controlPlane.snapshot()
        val staleSnapshotWithSameValues = actualSnapshot.copy(
            transitionGeneration = actualSnapshot.transitionGeneration - 1,
        )
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        val result = coordinator.advance(
            expectedSnapshot = staleSnapshotWithSameValues,
            delayStartedElapsedMillis = 10_000,
            nowElapsedMillis = 10_001,
            toggleDelay = 1.milliseconds,
        )

        val stale = result as RotationToggleDelayAdvanceResult.Stale
        assertEquals(staleSnapshotWithSameValues, stale.expectedSnapshot)
        assertEquals(actualSnapshot, stale.actualSnapshot)
        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
    }

    @Test
    fun `does not evaluate toggle delay when rotation is not waiting for toggle delay`() {
        val controlPlane = RotationControlPlane(
            initialStatus = RotationStatus(
                state = RotationState.RunningEnableCommand,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
            ),
        )
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        val result = coordinator.advance(
            expectedSnapshot = controlPlane.snapshot(),
            delayStartedElapsedMillis = -1,
            nowElapsedMillis = -1,
            toggleDelay = (-1).seconds,
        )

        assertTrue(result is RotationToggleDelayAdvanceResult.NoAction)
        assertEquals(RotationState.RunningEnableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `invalid toggle delay timing leaves control plane waiting for toggle delay`() {
        val controlPlane = waitingForToggleDelayControlPlane()
        val coordinator = RotationToggleDelayCoordinator(controlPlane = controlPlane)

        assertFailsWith<IllegalArgumentException> {
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                delayStartedElapsedMillis = -1,
                nowElapsedMillis = 11_000,
                toggleDelay = 3.seconds,
            )
        }

        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
    }

    private fun waitingForToggleDelayControlPlane(): RotationControlPlane =
        RotationControlPlane(
            initialStatus = RotationStatus(
                state = RotationState.WaitingForToggleDelay,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
            ),
        )
}
