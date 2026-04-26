package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RotationStartGateTest {
    @Test
    fun `accepted start with no prior terminal rotation advances past cooldown gate`() {
        val session = RotationSessionController()
        val terminalTimestamps = TerminalRotationTimestampTracker()
        val gate = RotationStartGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 10_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.startTransition.disposition)
        assertEquals(RotationState.CheckingCooldown, result.startTransition.status.state)
        assertEquals(RotationCooldownDecision.Passed, result.cooldownDecision)
        val cooldownTransition = assertNotNull(result.cooldownTransition)
        assertEquals(RotationTransitionDisposition.Accepted, cooldownTransition.disposition)
        assertEquals(RotationState.CheckingRoot, cooldownTransition.status.state)
        assertEquals(RotationState.CheckingRoot, result.status.state)
        assertEquals(result.status, session.currentStatus)
        assertNull(terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.NotTerminal,
            ),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `accepted start rejected by cooldown fails session without extending cooldown`() {
        val session = RotationSessionController()
        val terminalTimestamps = TerminalRotationTimestampTracker(
            initialLastTerminalElapsedMillis = 1_000,
        )
        val gate = RotationStartGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.requestStart(
            operation = RotationOperation.AirplaneMode,
            nowElapsedMillis = 121_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.startTransition.disposition)
        assertEquals(
            RotationCooldownDecision.Rejected(remainingCooldown = 60.seconds),
            result.cooldownDecision,
        )
        val cooldownTransition = assertNotNull(result.cooldownTransition)
        assertEquals(RotationTransitionDisposition.Accepted, cooldownTransition.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.CooldownActive, result.status.failureReason)
        assertEquals(result.status, session.currentStatus)
        assertEquals(1_000, terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.CooldownRejected,
            ),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `duplicate active start does not evaluate or apply cooldown`() {
        val session = RotationSessionController()
        val active = session.requestStart(RotationOperation.MobileData).status
        val terminalTimestamps = TerminalRotationTimestampTracker(
            initialLastTerminalElapsedMillis = 1_000,
        )
        val gate = RotationStartGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.requestStart(
            operation = RotationOperation.AirplaneMode,
            nowElapsedMillis = 181_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationTransitionDisposition.Duplicate, result.startTransition.disposition)
        assertEquals(active, result.startTransition.status)
        assertEquals(null, result.cooldownDecision)
        assertEquals(null, result.cooldownTransition)
        assertEquals(null, result.terminalTimestampObservation)
        assertEquals(active, result.status)
        assertEquals(active, session.currentStatus)
        assertEquals(1_000, terminalTimestamps.lastTerminalElapsedMillis)
    }

    @Test
    fun `invalid cooldown inputs are rejected before mutating idle session`() {
        val session = RotationSessionController()
        val gate = RotationStartGate(
            sessionController = session,
            terminalTimestampTracker = TerminalRotationTimestampTracker(),
        )

        assertFailsWith<IllegalArgumentException> {
            gate.requestStart(
                operation = RotationOperation.MobileData,
                nowElapsedMillis = -1,
                cooldown = 180.seconds,
            )
        }

        assertEquals(RotationStatus.idle(), session.currentStatus)

        assertFailsWith<IllegalArgumentException> {
            gate.requestStart(
                operation = RotationOperation.MobileData,
                nowElapsedMillis = 1_000,
                cooldown = (-1).seconds,
            )
        }

        assertEquals(RotationStatus.idle(), session.currentStatus)
    }
}
