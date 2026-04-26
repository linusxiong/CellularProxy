package com.cellularproxy.shared.rotation

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RotationControlPlaneTest {
    @Test
    fun `start and progress share one session and terminal timestamp tracker`() {
        val controlPlane = RotationControlPlane()

        val startResult = controlPlane.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 10_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationState.CheckingRoot, startResult.status.state)
        assertEquals(startResult.status, controlPlane.currentStatus)
        assertNull(controlPlane.lastTerminalElapsedMillis)

        val progressResult = controlPlane.applyProgress(
            event = RotationEvent.RootUnavailable,
            nowElapsedMillis = 12_000,
        )

        assertEquals(RotationTransitionDisposition.Accepted, progressResult.transition.disposition)
        assertEquals(RotationState.Failed, progressResult.status.state)
        assertEquals(RotationFailureReason.RootUnavailable, progressResult.status.failureReason)
        assertEquals(progressResult.status, controlPlane.currentStatus)
        assertEquals(12_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.Recorded(12_000),
            progressResult.terminalTimestampObservation,
        )
    }

    @Test
    fun `terminal timestamp from progress gates cooldown for the next start`() {
        val controlPlane = RotationControlPlane(
            initialStatus = RotationStatus(
                state = RotationState.ResumingProxyRequests,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.25",
                publicIpChanged = true,
            ),
        )

        controlPlane.applyProgress(
            event = RotationEvent.ProxyRequestsResumed,
            nowElapsedMillis = 20_000,
        )

        val rejectedStart = controlPlane.requestStart(
            operation = RotationOperation.AirplaneMode,
            nowElapsedMillis = 80_000,
            cooldown = 180.seconds,
        )

        assertEquals(
            RotationCooldownDecision.Rejected(remainingCooldown = 120.seconds),
            rejectedStart.cooldownDecision,
        )
        assertEquals(RotationState.Failed, rejectedStart.status.state)
        assertEquals(RotationFailureReason.CooldownActive, rejectedStart.status.failureReason)
        assertEquals(20_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `initial terminal timestamp participates in first cooldown check`() {
        val controlPlane = RotationControlPlane(
            initialLastTerminalElapsedMillis = 1_000,
        )

        val result = controlPlane.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 31_000,
            cooldown = 60.seconds,
        )

        assertEquals(
            RotationCooldownDecision.Rejected(remainingCooldown = 30.seconds),
            result.cooldownDecision,
        )
        assertEquals(RotationFailureReason.CooldownActive, result.status.failureReason)
        assertEquals(1_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `invalid initial terminal timestamp is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RotationControlPlane(initialLastTerminalElapsedMillis = -1)
        }
    }

    @Test
    fun `mutating operations are synchronized to linearize session and timestamp state`() {
        val requestStartMethod = assertNotNull(
            RotationControlPlane::class.java.declaredMethods.singleOrNull {
                it.name.startsWith("requestStart") && it.parameterCount == 3
            },
        )
        val applyProgressMethod = RotationControlPlane::class.java.getDeclaredMethod(
            "applyProgress",
            RotationEvent::class.java,
            Long::class.javaPrimitiveType,
        )

        assertTrue(requestStartMethod.modifiers.let(Modifier::isSynchronized))
        assertTrue(applyProgressMethod.modifiers.let(Modifier::isSynchronized))
    }

    @Test
    fun `separate status and timestamp getters use the same monitor as mutations`() {
        val currentStatusGetter = RotationControlPlane::class.java.getDeclaredMethod("getCurrentStatus")
        val lastTerminalGetter = RotationControlPlane::class.java.getDeclaredMethod(
            "getLastTerminalElapsedMillis",
        )

        assertTrue(currentStatusGetter.modifiers.let(Modifier::isSynchronized))
        assertTrue(lastTerminalGetter.modifiers.let(Modifier::isSynchronized))
    }

    @Test
    fun `snapshot reads status and terminal timestamp together`() {
        val controlPlane = RotationControlPlane(
            initialLastTerminalElapsedMillis = 5_000,
        )

        val snapshot = controlPlane.snapshot()

        assertEquals(RotationStatus.idle(), snapshot.status)
        assertEquals(5_000, snapshot.lastTerminalElapsedMillis)
        assertEquals(0, snapshot.transitionGeneration)
    }

    @Test
    fun `snapshot transition generation increments only after accepted mutations`() {
        val controlPlane = RotationControlPlane()

        val initialSnapshot = controlPlane.snapshot()
        controlPlane.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 1_000,
            cooldown = 60.seconds,
        )
        val activeSnapshot = controlPlane.snapshot()
        controlPlane.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 2_000,
            cooldown = 60.seconds,
        )
        val duplicateSnapshot = controlPlane.snapshot()

        assertEquals(0, initialSnapshot.transitionGeneration)
        assertEquals(1, activeSnapshot.transitionGeneration)
        assertEquals(activeSnapshot.transitionGeneration, duplicateSnapshot.transitionGeneration)
    }

    @Test
    fun `terminal initial status requires initial terminal timestamp`() {
        assertFailsWith<IllegalArgumentException> {
            RotationControlPlane(
                initialStatus = RotationStatus(
                    state = RotationState.Completed,
                    operation = RotationOperation.MobileData,
                    oldPublicIp = "198.51.100.10",
                    newPublicIp = "203.0.113.25",
                    publicIpChanged = true,
                ),
            )
        }

        val controlPlane = RotationControlPlane(
            initialStatus = RotationStatus(
                state = RotationState.Completed,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.25",
                publicIpChanged = true,
            ),
            initialLastTerminalElapsedMillis = 20_000,
        )

        assertEquals(RotationState.Completed, controlPlane.currentStatus.state)
        assertEquals(20_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `control plane snapshot rejects impossible terminal hydration`() {
        assertFailsWith<IllegalArgumentException> {
            RotationControlPlaneSnapshot(
                status = RotationStatus(
                    state = RotationState.Failed,
                    operation = RotationOperation.AirplaneMode,
                    failureReason = RotationFailureReason.RootUnavailable,
                ),
                lastTerminalElapsedMillis = null,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RotationControlPlaneSnapshot(
                status = RotationStatus.idle(),
                lastTerminalElapsedMillis = null,
                transitionGeneration = -1,
            )
        }

        val cooldownRejectedSnapshot = RotationControlPlaneSnapshot(
            status = RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.AirplaneMode,
                failureReason = RotationFailureReason.CooldownActive,
            ),
            lastTerminalElapsedMillis = null,
        )

        assertFalse(cooldownRejectedSnapshot.status.isActive)
    }
}
