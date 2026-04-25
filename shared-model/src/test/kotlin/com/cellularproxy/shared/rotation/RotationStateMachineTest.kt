package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RotationStateMachineTest {
    @Test
    fun `start request enters cooldown check for the requested operation`() {
        val result = RotationStateMachine.transition(
            status = RotationStatus.idle(),
            event = RotationEvent.StartRequested(RotationOperation.MobileData),
        )

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.CheckingCooldown, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertNull(result.status.failureReason)
    }

    @Test
    fun `duplicate start request while rotation is active leaves status unchanged`() {
        val active = RotationStateMachine.transition(
            status = RotationStatus.idle(),
            event = RotationEvent.StartRequested(RotationOperation.AirplaneMode),
        ).status

        val duplicate = RotationStateMachine.transition(
            status = active,
            event = RotationEvent.StartRequested(RotationOperation.MobileData),
        )

        assertEquals(RotationTransitionDisposition.Duplicate, duplicate.disposition)
        assertEquals(active, duplicate.status)
    }

    @Test
    fun `old public ip success pauses new requests before draining connections`() {
        val probing = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
        )

        val pausing = RotationStateMachine.transition(
            status = probing,
            event = RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
        ).status
        val draining = RotationStateMachine.transition(
            status = pausing,
            event = RotationEvent.NewRequestsPaused,
        ).status

        assertEquals(RotationState.PausingNewRequests, pausing.state)
        assertEquals(RotationState.DrainingConnections, draining.state)
    }

    @Test
    fun `successful mobile data rotation requires disable delay enable and resume`() {
        val completed = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataDisable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataEnable)),
            RotationEvent.NetworkReturned,
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "203.0.113.20",
                strictIpChangeRequired = true,
            ),
            RotationEvent.ProxyRequestsResumed,
        )

        assertEquals(RotationState.Completed, completed.state)
        assertEquals(RotationOperation.MobileData, completed.operation)
        assertEquals("198.51.100.10", completed.oldPublicIp)
        assertEquals("203.0.113.20", completed.newPublicIp)
        assertEquals(true, completed.publicIpChanged)
        assertNull(completed.failureReason)
    }

    @Test
    fun `successful airplane mode rotation requires enable delay disable and resume`() {
        val completed = statusAfter(
            RotationEvent.StartRequested(RotationOperation.AirplaneMode),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.AirplaneModeEnable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.AirplaneModeDisable)),
            RotationEvent.NetworkReturned,
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "203.0.113.20",
                strictIpChangeRequired = true,
            ),
            RotationEvent.ProxyRequestsResumed,
        )

        assertEquals(RotationState.Completed, completed.state)
        assertEquals(RotationOperation.AirplaneMode, completed.operation)
    }

    @Test
    fun `first successful root command waits for configured toggle delay before second command`() {
        val waitingForDelay = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataDisable)),
        )
        val runningEnable = RotationStateMachine.transition(
            status = waitingForDelay,
            event = RotationEvent.ToggleDelayElapsed,
        ).status

        assertEquals(RotationState.WaitingForToggleDelay, waitingForDelay.state)
        assertEquals(RotationState.RunningEnableCommand, runningEnable.state)
    }

    @Test
    fun `strict ip change failure is recorded only after proxy requests resume`() {
        val resuming = statusAfter(
            RotationEvent.StartRequested(RotationOperation.AirplaneMode),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.AirplaneModeEnable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.AirplaneModeDisable)),
            RotationEvent.NetworkReturned,
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "198.51.100.10",
                strictIpChangeRequired = true,
            ),
        )
        val failed = RotationStateMachine.transition(
            status = resuming,
            event = RotationEvent.ProxyRequestsResumed,
        ).status

        assertEquals(RotationState.ResumingProxyRequests, resuming.state)
        assertEquals(RotationState.Failed, failed.state)
        assertEquals(RotationFailureReason.StrictIpChangeRequired, failed.failureReason)
        assertEquals("198.51.100.10", failed.oldPublicIp)
        assertEquals("198.51.100.10", failed.newPublicIp)
        assertEquals(false, failed.publicIpChanged)
    }

    @Test
    fun `unchanged public ip still completes when strict ip change is disabled`() {
        val completed = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("203.0.113.44"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataDisable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataEnable)),
            RotationEvent.NetworkReturned,
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "203.0.113.44",
                strictIpChangeRequired = false,
            ),
            RotationEvent.ProxyRequestsResumed,
        )

        assertEquals(RotationState.Completed, completed.state)
        assertEquals(false, completed.publicIpChanged)
        assertNull(completed.failureReason)
    }

    @Test
    fun `precondition failures before request pause become terminal failed statuses`() {
        assertFailureAfter(
            expected = RotationFailureReason.CooldownActive,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownRejected,
        )
        assertFailureAfter(
            expected = RotationFailureReason.RootUnavailable,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootUnavailable,
        )
        assertFailureAfter(
            expected = RotationFailureReason.OldPublicIpProbeFailed,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeFailed,
        )
    }

    @Test
    fun `failures after request pause resume proxy requests before terminal failure`() {
        assertFailureAfterResume(
            expected = RotationFailureReason.RootCommandFailed,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(
                RootCommandResult.completed(
                    category = RootCommandCategory.MobileDataDisable,
                    exitCode = 1,
                    stdout = "",
                    stderr = "svc failed",
                ),
            ),
        )
        assertFailureAfterResume(
            expected = RotationFailureReason.RootCommandTimedOut,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(
                RootCommandResult.timedOut(
                    category = RootCommandCategory.MobileDataDisable,
                    stdout = "",
                    stderr = "timeout",
                ),
            ),
        )
        assertFailureAfterResume(
            expected = RotationFailureReason.NetworkReturnTimedOut,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataDisable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataEnable)),
            RotationEvent.NetworkReturnTimedOut,
        )
        assertFailureAfterResume(
            expected = RotationFailureReason.NewPublicIpProbeFailed,
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataDisable)),
            RotationEvent.ToggleDelayElapsed,
            RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataEnable)),
            RotationEvent.NetworkReturned,
            RotationEvent.NewPublicIpProbeFailed,
        )
    }

    @Test
    fun `wrong command category is ignored for the current operation phase`() {
        val runningDisable = statusAfter(
            RotationEvent.StartRequested(RotationOperation.MobileData),
            RotationEvent.CooldownPassed,
            RotationEvent.RootAvailable,
            RotationEvent.OldPublicIpProbeSucceeded("198.51.100.10"),
            RotationEvent.NewRequestsPaused,
            RotationEvent.ConnectionsDrained,
        )

        val ignored = RotationStateMachine.transition(
            status = runningDisable,
            event = RotationEvent.RootCommandCompleted(success(RootCommandCategory.MobileDataEnable)),
        )

        assertEquals(RotationTransitionDisposition.Ignored, ignored.disposition)
        assertEquals(runningDisable, ignored.status)
    }

    @Test
    fun `out of order events are ignored without mutating status`() {
        val idle = RotationStatus.idle()
        val ignored = RotationStateMachine.transition(idle, RotationEvent.RootAvailable)

        assertEquals(RotationTransitionDisposition.Ignored, ignored.disposition)
        assertEquals(idle, ignored.status)
    }

    @Test
    fun `rotation status rejects stale or contradictory metadata`() {
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.Idle,
                operation = RotationOperation.MobileData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.Completed,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.20",
                publicIpChanged = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.MobileData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.RunningDisableCommand,
                operation = RotationOperation.MobileData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.CheckingCooldown,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.ResumingProxyRequests,
                operation = RotationOperation.MobileData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationStatus(
                state = RotationState.ResumingProxyRequests,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.20",
            )
        }
    }

    private fun assertFailureAfter(
        expected: RotationFailureReason,
        vararg events: RotationEvent,
    ) {
        val failed = statusAfter(*events)

        assertEquals(RotationState.Failed, failed.state)
        assertEquals(expected, failed.failureReason)
    }

    private fun assertFailureAfterResume(
        expected: RotationFailureReason,
        vararg events: RotationEvent,
    ) {
        val resuming = statusAfter(*events)
        val failed = RotationStateMachine.transition(
            status = resuming,
            event = RotationEvent.ProxyRequestsResumed,
        ).status

        assertEquals(RotationState.ResumingProxyRequests, resuming.state)
        assertEquals(RotationState.Failed, failed.state)
        assertEquals(expected, failed.failureReason)
    }

    private fun statusAfter(vararg events: RotationEvent): RotationStatus =
        events.fold(RotationStatus.idle()) { status, event ->
            val result = RotationStateMachine.transition(status, event)
            assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
            result.status
        }

    private fun success(category: RootCommandCategory): RootCommandResult =
        RootCommandResult.completed(
            category = category,
            exitCode = 0,
            stdout = "ok",
            stderr = "",
        )
}
