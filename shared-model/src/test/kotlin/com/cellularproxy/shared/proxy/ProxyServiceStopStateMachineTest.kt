package com.cellularproxy.shared.proxy

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyServiceStopStateMachineTest {
    @Test
    fun `stop request moves running proxy to stopping while preserving status context`() {
        val status = ProxyServiceStatus.running(
            listenHost = "0.0.0.0",
            listenPort = 8080,
            configuredRoute = RouteTarget.Cellular,
            boundRoute = NetworkDescriptor(
                id = "cell-1",
                category = NetworkCategory.Cellular,
                displayName = "Carrier LTE",
                isAvailable = true,
            ),
            publicIp = "198.51.100.23",
            hasHighSecurityRisk = true,
            metrics = ProxyTrafficMetrics(
                activeConnections = 2,
                totalConnections = 5,
                rejectedConnections = 1,
                bytesReceived = 42,
                bytesSent = 99,
            ),
        )

        val result = ProxyServiceStopStateMachine.transition(
            current = status,
            event = ProxyServiceStopEvent.StopRequested,
        )

        assertEquals(ProxyServiceStopTransitionDisposition.Accepted, result.disposition)
        assertEquals(true, result.accepted)
        assertEquals(status.copy(state = ProxyServiceState.Stopping), result.status)
    }

    @Test
    fun `stop request moves starting proxy to stopping`() {
        val status = ProxyServiceStatus(state = ProxyServiceState.Starting)

        val result = ProxyServiceStopStateMachine.transition(
            current = status,
            event = ProxyServiceStopEvent.StopRequested,
        )

        assertEquals(ProxyServiceStopTransitionDisposition.Accepted, result.disposition)
        assertEquals(ProxyServiceState.Stopping, result.status.state)
    }

    @Test
    fun `stop request is duplicate while service is already stopping`() {
        val status = ProxyServiceStatus(
            state = ProxyServiceState.Stopping,
            metrics = ProxyTrafficMetrics(activeConnections = 1, totalConnections = 3),
        )

        val result = ProxyServiceStopStateMachine.transition(
            current = status,
            event = ProxyServiceStopEvent.StopRequested,
        )

        assertEquals(ProxyServiceStopTransitionDisposition.Duplicate, result.disposition)
        assertEquals(false, result.accepted)
        assertEquals(status, result.status)
    }

    @Test
    fun `stop request is ignored when service is already terminal`() {
        val stopped = ProxyServiceStatus.stopped()
        val failed = ProxyServiceStatus.failed(ProxyStartupError.PortAlreadyInUse)

        val stoppedResult = ProxyServiceStopStateMachine.transition(stopped, ProxyServiceStopEvent.StopRequested)
        val failedResult = ProxyServiceStopStateMachine.transition(failed, ProxyServiceStopEvent.StopRequested)

        assertEquals(ProxyServiceStopTransitionDisposition.Ignored, stoppedResult.disposition)
        assertEquals(stopped, stoppedResult.status)
        assertEquals(ProxyServiceStopTransitionDisposition.Ignored, failedResult.disposition)
        assertEquals(failed, failedResult.status)
    }
}
