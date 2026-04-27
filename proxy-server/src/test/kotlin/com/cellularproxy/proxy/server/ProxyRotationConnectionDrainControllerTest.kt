package com.cellularproxy.proxy.server

import com.cellularproxy.shared.rotation.RotationConnectionDrainDecision
import com.cellularproxy.shared.rotation.RotationConnectionDrainReason
import com.cellularproxy.shared.rotation.RotationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ProxyRotationConnectionDrainControllerTest {
    @Test
    fun `runtime drain completes when no admitted proxy exchanges are active`() {
        val controller =
            ProxyRotationConnectionDrainController(
                activeProxyExchanges = { 0 },
            )

        val decision =
            controller.evaluate(
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
    fun `runtime drain waits using admitted proxy exchanges rather than accepted socket reservations`() {
        val controller =
            ProxyRotationConnectionDrainController(
                activeProxyExchanges = { 2 },
            )

        val decision =
            controller.evaluate(
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 11_000,
                maxDrainTime = 30.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 2,
                remainingDrainTime = 20.seconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `runtime drain advances when max drain time elapses with admitted proxy exchanges still active`() {
        val controller =
            ProxyRotationConnectionDrainController(
                activeProxyExchanges = { 1 },
            )

        val decision =
            controller.evaluate(
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 31_000,
                maxDrainTime = 30.seconds,
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
    fun `runtime drain caps implausibly large active exchange counts instead of overflowing`() {
        val controller =
            ProxyRotationConnectionDrainController(
                activeProxyExchanges = { Long.MAX_VALUE },
            )

        val decision =
            controller.evaluate(
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 2_000,
                maxDrainTime = 30.seconds,
            )

        assertEquals(
            RotationConnectionDrainDecision.Waiting(
                activeConnections = Int.MAX_VALUE,
                remainingDrainTime = 29.seconds,
            ),
            decision,
        )
    }

    @Test
    fun `runtime drain rejects negative observed active exchange counts`() {
        val controller =
            ProxyRotationConnectionDrainController(
                activeProxyExchanges = { -1 },
            )

        assertFailsWith<IllegalStateException> {
            controller.evaluate(
                drainStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                maxDrainTime = 30.seconds,
            )
        }
    }
}
