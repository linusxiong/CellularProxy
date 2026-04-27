package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RotationToggleDelayPolicyTest {
    @Test
    fun `rotation toggle delay elapses after configured wait time`() {
        val decision =
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 1_000,
                nowElapsedMillis = 4_000,
                toggleDelay = 3.seconds,
            )

        assertEquals(RotationToggleDelayDecision.Elapsed, decision)
        assertEquals(RotationEvent.ToggleDelayElapsed, decision.event)
    }

    @Test
    fun `rotation toggle delay waits while configured wait time remains`() {
        val decision =
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 1_000,
                nowElapsedMillis = 2_250,
                toggleDelay = 3.seconds,
            )

        assertEquals(
            RotationToggleDelayDecision.Waiting(
                remainingDelay = 1_750.milliseconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `zero rotation toggle delay elapses immediately`() {
        val decision =
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                toggleDelay = 0.seconds,
            )

        assertEquals(RotationToggleDelayDecision.Elapsed, decision)
        assertEquals(RotationEvent.ToggleDelayElapsed, decision.event)
    }

    @Test
    fun `rotation toggle delay fails closed when delay start time is observed in the future`() {
        val decision =
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 5_000,
                nowElapsedMillis = 4_000,
                toggleDelay = 3.seconds,
            )

        assertEquals(
            RotationToggleDelayDecision.Waiting(
                remainingDelay = 4.seconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `invalid rotation toggle delay inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = -1,
                nowElapsedMillis = 1_000,
                toggleDelay = 3.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 1_000,
                nowElapsedMillis = -1,
                toggleDelay = 3.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationToggleDelayPolicy.evaluate(
                delayStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                toggleDelay = (-1).seconds,
            )
        }
    }

    @Test
    fun `public waiting decision rejects non-positive remaining delay`() {
        assertFailsWith<IllegalArgumentException> {
            RotationToggleDelayDecision.Waiting(remainingDelay = 0.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationToggleDelayDecision.Waiting(remainingDelay = (-1).milliseconds)
        }
    }
}
