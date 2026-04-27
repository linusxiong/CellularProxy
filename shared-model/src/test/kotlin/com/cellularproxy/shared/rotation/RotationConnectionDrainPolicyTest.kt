package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RotationConnectionDrainPolicyTest {
    @Test
    fun `rotation drain completes immediately when no proxy connections are active`() {
        val decision =
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 0,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                maxDrainTime = 30.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.NoActiveConnections,
                activeConnections = 0,
            ),
            decision,
        )
        assertEquals(RotationEvent.ConnectionsDrained, decision.event)
    }

    @Test
    fun `rotation drain waits while active connections remain before max drain time`() {
        val decision =
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 2,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 11_250,
                maxDrainTime = 30.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 2,
                remainingDrainTime = 19_750.milliseconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `rotation drain completes when max drain time has elapsed with active connections remaining`() {
        val decision =
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 3,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 31_000,
                maxDrainTime = 30.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.MaxDrainTimeElapsed,
                activeConnections = 3,
            ),
            decision,
        )
        assertEquals(RotationEvent.ConnectionsDrained, decision.event)
    }

    @Test
    fun `zero max drain time completes immediately even when active connections remain`() {
        val decision =
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 1,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                maxDrainTime = 0.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.MaxDrainTimeElapsed,
                activeConnections = 1,
            ),
            decision,
        )
        assertEquals(RotationEvent.ConnectionsDrained, decision.event)
    }

    @Test
    fun `rotation drain fails closed when drain start time is observed in the future`() {
        val decision =
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 4,
                drainStartedElapsedMillis = 5_000,
                nowElapsedMillis = 4_000,
                maxDrainTime = 3.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 4,
                remainingDrainTime = 4.seconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `invalid rotation drain inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = -1,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                maxDrainTime = 30.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 0,
                drainStartedElapsedMillis = -1,
                nowElapsedMillis = 1_000,
                maxDrainTime = 30.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 0,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = -1,
                maxDrainTime = 30.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainPolicy.evaluate(
                activeConnections = 0,
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                maxDrainTime = (-1).seconds,
            )
        }
    }

    @Test
    fun `public rotation drain decisions reject contradictory data`() {
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.NoActiveConnections,
                activeConnections = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.MaxDrainTimeElapsed,
                activeConnections = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 0,
                remainingDrainTime = 1.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 1,
                remainingDrainTime = 0.seconds,
            )
        }
    }
}
