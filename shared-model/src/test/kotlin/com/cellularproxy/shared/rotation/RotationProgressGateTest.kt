package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RotationProgressGateTest {
    @Test
    fun `accepted non terminal progress event updates session without recording terminal timestamp`() {
        val session = RotationSessionController(
            initialStatus = RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.MobileData,
            ),
        )
        val terminalTimestamps = TerminalRotationTimestampTracker(
            initialLastTerminalElapsedMillis = 1_000,
        )
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.apply(
            event = RotationEvent.RootAvailable,
            nowElapsedMillis = 2_000,
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(RotationState.ProbingOldPublicIp, result.status.state)
        assertEquals(result.status, session.currentStatus)
        assertEquals(1_000, terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.NotTerminal,
            ),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `accepted terminal completion records terminal timestamp`() {
        val session = RotationSessionController(
            initialStatus = RotationStatus(
                state = RotationState.ResumingProxyRequests,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.25",
                publicIpChanged = true,
            ),
        )
        val terminalTimestamps = TerminalRotationTimestampTracker()
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.apply(
            event = RotationEvent.ProxyRequestsResumed,
            nowElapsedMillis = 9_000,
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(RotationState.Completed, result.status.state)
        assertEquals(9_000, terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.Recorded(9_000),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `accepted real failure records terminal timestamp`() {
        val session = RotationSessionController(
            initialStatus = RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.AirplaneMode,
            ),
        )
        val terminalTimestamps = TerminalRotationTimestampTracker()
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.apply(
            event = RotationEvent.RootUnavailable,
            nowElapsedMillis = 7_000,
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(7_000, terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.Recorded(7_000),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `ignored progress event does not mutate session or record terminal timestamp`() {
        val initialStatus = RotationStatus(
            state = RotationState.CheckingRoot,
            operation = RotationOperation.MobileData,
        )
        val session = RotationSessionController(initialStatus = initialStatus)
        val terminalTimestamps = TerminalRotationTimestampTracker(
            initialLastTerminalElapsedMillis = 1_000,
        )
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = terminalTimestamps,
        )

        val result = gate.apply(
            event = RotationEvent.ToggleDelayElapsed,
            nowElapsedMillis = 5_000,
        )

        assertEquals(RotationTransitionDisposition.Ignored, result.transition.disposition)
        assertEquals(initialStatus, result.status)
        assertEquals(initialStatus, session.currentStatus)
        assertEquals(1_000, terminalTimestamps.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
            ),
            result.terminalTimestampObservation,
        )
    }

    @Test
    fun `start gate events are rejected so callers cannot bypass cooldown protection`() {
        val session = RotationSessionController()
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = TerminalRotationTimestampTracker(),
        )

        assertFailsWith<IllegalArgumentException> {
            gate.apply(
                event = RotationEvent.StartRequested(RotationOperation.MobileData),
                nowElapsedMillis = 1_000,
            )
        }

        assertEquals(RotationStatus.idle(), session.currentStatus)

        val checkingCooldownSession = RotationSessionController(
            initialStatus = RotationStatus(
                state = RotationState.CheckingCooldown,
                operation = RotationOperation.AirplaneMode,
            ),
        )
        val checkingCooldownGate = RotationProgressGate(
            sessionController = checkingCooldownSession,
            terminalTimestampTracker = TerminalRotationTimestampTracker(),
        )

        assertFailsWith<IllegalArgumentException> {
            checkingCooldownGate.apply(
                event = RotationEvent.CooldownPassed,
                nowElapsedMillis = 2_000,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            checkingCooldownGate.apply(
                event = RotationEvent.CooldownRejected,
                nowElapsedMillis = 2_000,
            )
        }

        assertEquals(
            RotationStatus(
                state = RotationState.CheckingCooldown,
                operation = RotationOperation.AirplaneMode,
            ),
            checkingCooldownSession.currentStatus,
        )
    }

    @Test
    fun `invalid progress observation timestamp is rejected before mutating session`() {
        val initialStatus = RotationStatus(
            state = RotationState.CheckingRoot,
            operation = RotationOperation.MobileData,
        )
        val session = RotationSessionController(initialStatus = initialStatus)
        val gate = RotationProgressGate(
            sessionController = session,
            terminalTimestampTracker = TerminalRotationTimestampTracker(),
        )

        assertFailsWith<IllegalArgumentException> {
            gate.apply(
                event = RotationEvent.RootAvailable,
                nowElapsedMillis = -1,
            )
        }

        assertEquals(initialStatus, session.currentStatus)
    }
}
