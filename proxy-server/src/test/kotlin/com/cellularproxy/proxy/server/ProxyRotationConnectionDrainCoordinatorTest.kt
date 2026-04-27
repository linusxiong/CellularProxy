package com.cellularproxy.proxy.server

import com.cellularproxy.shared.rotation.RotationConnectionDrainDecision
import com.cellularproxy.shared.rotation.RotationConnectionDrainReason
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyRotationConnectionDrainCoordinatorTest {
    @Test
    fun `drained runtime proxy exchanges advance control plane to disable command`() {
        val controlPlane = drainingControlPlane()
        val tracker =
            LockRecordingActiveProxyExchanges(
                controlPlane = controlPlane,
                activeExchanges = 0,
            )
        val coordinator =
            ProxyRotationConnectionDrainCoordinator(
                drainController = ProxyRotationConnectionDrainController(tracker::count),
                controlPlane = controlPlane,
            )

        val result =
            coordinator.advance(
                drainStartedElapsedMillis = 10_000,
                nowElapsedMillis = 11_000,
                maxDrainTime = 30.seconds,
            )

        val applied = result as ProxyRotationConnectionDrainAdvanceResult.Applied
        assertEquals(
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.NoActiveConnections,
                activeConnections = 0,
            ),
            applied.decision,
        )
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
        assertEquals(true, tracker.sampleHeldControlPlaneLock)
    }

    @Test
    fun `waiting drain decision leaves control plane in draining connections`() {
        val controlPlane = drainingControlPlane()
        val coordinator =
            ProxyRotationConnectionDrainCoordinator(
                drainController =
                    ProxyRotationConnectionDrainController(
                        activeProxyExchanges = { 2 },
                    ),
                controlPlane = controlPlane,
            )

        val result =
            coordinator.advance(
                drainStartedElapsedMillis = 10_000,
                nowElapsedMillis = 20_000,
                maxDrainTime = 30.seconds,
            )

        val waiting = result as ProxyRotationConnectionDrainAdvanceResult.Waiting
        assertEquals(
            RotationConnectionDrainDecision.Waiting(
                activeConnections = 2,
                remainingDrainTime = 20.seconds,
            ),
            waiting.decision,
        )
        assertEquals(RotationState.DrainingConnections, waiting.snapshot.status.state)
        assertEquals(RotationState.DrainingConnections, controlPlane.currentStatus.state)
    }

    @Test
    fun `max drain timeout advances control plane even with active proxy exchanges`() {
        val controlPlane = drainingControlPlane()
        val coordinator =
            ProxyRotationConnectionDrainCoordinator(
                drainController =
                    ProxyRotationConnectionDrainController(
                        activeProxyExchanges = { 1 },
                    ),
                controlPlane = controlPlane,
            )

        val result =
            coordinator.advance(
                drainStartedElapsedMillis = 10_000,
                nowElapsedMillis = 40_000,
                maxDrainTime = 30.seconds,
            )

        val applied = result as ProxyRotationConnectionDrainAdvanceResult.Applied
        assertEquals(
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.MaxDrainTimeElapsed,
                activeConnections = 1,
            ),
            applied.decision,
        )
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `does not sample active proxy exchanges when rotation is not draining`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.RunningDisableCommand,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val coordinator =
            ProxyRotationConnectionDrainCoordinator(
                drainController =
                    ProxyRotationConnectionDrainController(
                        activeProxyExchanges = {
                            error("Active proxy exchanges should not be sampled outside drain state")
                        },
                    ),
                controlPlane = controlPlane,
            )

        val result =
            coordinator.advance(
                drainStartedElapsedMillis = 10_000,
                nowElapsedMillis = 11_000,
                maxDrainTime = 30.seconds,
            )

        assertTrue(result is ProxyRotationConnectionDrainAdvanceResult.NoAction)
        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `invalid drain timing leaves control plane in draining state`() {
        val controlPlane = drainingControlPlane()
        val coordinator =
            ProxyRotationConnectionDrainCoordinator(
                drainController =
                    ProxyRotationConnectionDrainController(
                        activeProxyExchanges = { 0 },
                    ),
                controlPlane = controlPlane,
            )

        assertFailsWith<IllegalArgumentException> {
            coordinator.advance(
                drainStartedElapsedMillis = -1,
                nowElapsedMillis = 11_000,
                maxDrainTime = 30.seconds,
            )
        }

        assertEquals(RotationState.DrainingConnections, controlPlane.currentStatus.state)
    }

    private fun drainingControlPlane(): RotationControlPlane =
        RotationControlPlane(
            initialStatus =
                RotationStatus(
                    state = RotationState.DrainingConnections,
                    operation = RotationOperation.MobileData,
                    oldPublicIp = "198.51.100.10",
                ),
        )

    private class LockRecordingActiveProxyExchanges(
        private val controlPlane: RotationControlPlane,
        private val activeExchanges: Long,
    ) {
        var sampleHeldControlPlaneLock: Boolean? = null
            private set

        fun count(): Long {
            sampleHeldControlPlaneLock = Thread.holdsLock(controlPlane)
            return activeExchanges
        }
    }
}
