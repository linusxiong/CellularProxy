package com.cellularproxy.shared.cloudflare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareTunnelStateMachineTest {
    @Test
    fun `default status is disabled and remote management is unavailable`() {
        val status = CloudflareTunnelStatus.disabled()

        assertEquals(CloudflareTunnelState.Disabled, status.state)
        assertNull(status.failureReason)
        assertFalse(status.isRemoteManagementAvailable)
    }

    @Test
    fun `start request moves disabled stopped and failed tunnels to starting`() {
        val failed = CloudflareTunnelStatus.failed("edge connection failed")

        val fromDisabled = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.disabled(),
            CloudflareTunnelEvent.StartRequested,
        )
        val fromStopped = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.stopped(),
            CloudflareTunnelEvent.StartRequested,
        )
        val fromFailed = CloudflareTunnelStateMachine.transition(failed, CloudflareTunnelEvent.StartRequested)

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, fromDisabled.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), fromDisabled.status)
        assertEquals(CloudflareTunnelStatus.starting(), fromStopped.status)
        assertEquals(CloudflareTunnelStatus.starting(), fromFailed.status)
    }

    @Test
    fun `start request is duplicate while tunnel is already active`() {
        val starting = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.starting(),
            CloudflareTunnelEvent.StartRequested,
        )
        val connected = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.connected(),
            CloudflareTunnelEvent.StartRequested,
        )
        val degraded = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.degraded(),
            CloudflareTunnelEvent.StartRequested,
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, starting.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), starting.status)
        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, connected.disposition)
        assertEquals(CloudflareTunnelStatus.connected(), connected.status)
        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, degraded.disposition)
        assertEquals(CloudflareTunnelStatus.degraded(), degraded.status)
    }

    @Test
    fun `connected and degraded events update active tunnel state`() {
        val connected = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.starting(),
            CloudflareTunnelEvent.Connected,
        )
        val degraded = CloudflareTunnelStateMachine.transition(
            connected.status,
            CloudflareTunnelEvent.Degraded,
        )
        val recovered = CloudflareTunnelStateMachine.transition(
            degraded.status,
            CloudflareTunnelEvent.Connected,
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, connected.disposition)
        assertEquals(CloudflareTunnelStatus.connected(), connected.status)
        assertTrue(connected.status.isRemoteManagementAvailable)

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, degraded.disposition)
        assertEquals(CloudflareTunnelStatus.degraded(), degraded.status)
        assertFalse(degraded.status.isRemoteManagementAvailable)

        assertEquals(CloudflareTunnelStatus.connected(), recovered.status)
    }

    @Test
    fun `failure event moves active tunnel to failed with reason`() {
        val result = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.connected(),
            CloudflareTunnelEvent.Failed("edge error Authorization: bearer-token"),
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.disposition)
        assertEquals(CloudflareTunnelState.Failed, result.status.state)
        assertEquals("edge error Authorization: bearer-token", result.status.failureReason)
        assertFalse(result.status.isRemoteManagementAvailable)
    }

    @Test
    fun `stop request stops only active or failed tunnels and does not resurrect disabled tunnels`() {
        val fromConnected = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.connected(),
            CloudflareTunnelEvent.StopRequested,
        )
        val fromFailed = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.failed("edge connection failed"),
            CloudflareTunnelEvent.StopRequested,
        )
        val fromDisabled = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.disabled(),
            CloudflareTunnelEvent.StopRequested,
        )
        val fromStopped = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.stopped(),
            CloudflareTunnelEvent.StopRequested,
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, fromConnected.disposition)
        assertEquals(CloudflareTunnelStatus.stopped(), fromConnected.status)
        assertEquals(CloudflareTunnelStatus.stopped(), fromFailed.status)

        assertEquals(CloudflareTunnelTransitionDisposition.Ignored, fromDisabled.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), fromDisabled.status)
        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, fromStopped.disposition)
        assertEquals(CloudflareTunnelStatus.stopped(), fromStopped.status)
    }

    @Test
    fun `disable request always ends disabled and duplicate disable is reported`() {
        val fromConnected = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.connected(),
            CloudflareTunnelEvent.DisableRequested,
        )
        val fromDisabled = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.disabled(),
            CloudflareTunnelEvent.DisableRequested,
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, fromConnected.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), fromConnected.status)
        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, fromDisabled.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), fromDisabled.status)
    }

    @Test
    fun `stale lifecycle events are ignored when tunnel is inactive`() {
        val connectedWhileDisabled = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.disabled(),
            CloudflareTunnelEvent.Connected,
        )
        val failedWhileStopped = CloudflareTunnelStateMachine.transition(
            CloudflareTunnelStatus.stopped(),
            CloudflareTunnelEvent.Failed("late failure"),
        )

        assertEquals(CloudflareTunnelTransitionDisposition.Ignored, connectedWhileDisabled.disposition)
        assertEquals(CloudflareTunnelStatus.disabled(), connectedWhileDisabled.status)
        assertEquals(CloudflareTunnelTransitionDisposition.Ignored, failedWhileStopped.disposition)
        assertEquals(CloudflareTunnelStatus.stopped(), failedWhileStopped.status)
    }

    @Test
    fun `failure reasons are required only for failed statuses`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelStatus.failed("")
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelStatus.failed("   ")
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelStatus(CloudflareTunnelState.Connected, failureReason = "stale reason")
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelStatus(CloudflareTunnelState.Failed, failureReason = null)
        }
    }
}
