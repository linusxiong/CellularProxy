package com.cellularproxy.shared.rotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RotationCooldownPolicyTest {
    @Test
    fun `rotation cooldown passes when there is no prior terminal rotation`() {
        val decision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = null,
            nowElapsedMillis = 1_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationCooldownDecision.Passed, decision)
        assertEquals(RotationEvent.CooldownPassed, decision.event)
    }

    @Test
    fun `rotation cooldown passes when configured cooldown has elapsed exactly`() {
        val decision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = 1_000,
            nowElapsedMillis = 181_000,
            cooldown = 180.seconds,
        )

        assertEquals(RotationCooldownDecision.Passed, decision)
        assertEquals(RotationEvent.CooldownPassed, decision.event)
    }

    @Test
    fun `rotation cooldown rejection reports remaining cooldown and state-machine event`() {
        val decision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = 1_000,
            nowElapsedMillis = 121_500,
            cooldown = 180.seconds,
        )

        assertEquals(
            RotationCooldownDecision.Rejected(remainingCooldown = 59_500.milliseconds),
            decision,
        )
        assertEquals(RotationEvent.CooldownRejected, decision.event)
    }

    @Test
    fun `rotation cooldown fails closed when observed last terminal time is in the future`() {
        val decision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = 5_000,
            nowElapsedMillis = 4_000,
            cooldown = 3.seconds,
        )

        assertEquals(
            RotationCooldownDecision.Rejected(remainingCooldown = 4.seconds),
            decision,
        )
        assertEquals(RotationEvent.CooldownRejected, decision.event)
    }

    @Test
    fun `zero cooldown passes immediately after a prior terminal rotation`() {
        val decision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = 1_000,
            nowElapsedMillis = 1_000,
            cooldown = 0.seconds,
        )

        assertEquals(RotationCooldownDecision.Passed, decision)
    }

    @Test
    fun `invalid cooldown inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RotationCooldownPolicy.evaluate(
                lastTerminalRotationElapsedMillis = -1,
                nowElapsedMillis = 1_000,
                cooldown = 180.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationCooldownPolicy.evaluate(
                lastTerminalRotationElapsedMillis = null,
                nowElapsedMillis = -1,
                cooldown = 180.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationCooldownPolicy.evaluate(
                lastTerminalRotationElapsedMillis = null,
                nowElapsedMillis = 1_000,
                cooldown = (-1).seconds,
            )
        }
    }
}
