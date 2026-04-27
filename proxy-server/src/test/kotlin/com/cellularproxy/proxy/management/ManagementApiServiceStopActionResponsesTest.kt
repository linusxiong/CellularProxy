package com.cellularproxy.proxy.management

import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionDisposition
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagementApiServiceStopActionResponsesTest {
    @Test
    fun `accepted service stop transition renders accepted response`() {
        val response =
            ManagementApiServiceStopActionResponses.transition(
                ProxyServiceStopTransitionResult(
                    disposition = ProxyServiceStopTransitionDisposition.Accepted,
                    status =
                        ProxyServiceStatus(
                            state = ProxyServiceState.Stopping,
                            metrics = ProxyTrafficMetrics(activeConnections = 2, totalConnections = 5),
                        ),
                ),
            )

        assertEquals(202, response.statusCode)
        assertEquals(
            """{"accepted":true,"disposition":"accepted","service":{"state":"stopping","activeConnections":2}}""",
            response.body,
        )
    }

    @Test
    fun `duplicate service stop transition renders conflict response`() {
        val response =
            ManagementApiServiceStopActionResponses.transition(
                ProxyServiceStopTransitionResult(
                    disposition = ProxyServiceStopTransitionDisposition.Duplicate,
                    status =
                        ProxyServiceStatus(
                            state = ProxyServiceState.Stopping,
                            metrics = ProxyTrafficMetrics(activeConnections = 1, totalConnections = 3),
                        ),
                ),
            )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"duplicate","service":{"state":"stopping","activeConnections":1}}""",
            response.body,
        )
    }

    @Test
    fun `ignored service stop transition renders terminal state without startup detail`() {
        val response =
            ManagementApiServiceStopActionResponses.transition(
                ProxyServiceStopTransitionResult(
                    disposition = ProxyServiceStopTransitionDisposition.Ignored,
                    status = ProxyServiceStatus.failed(ProxyStartupError.PortAlreadyInUse),
                ),
            )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"ignored","service":{"state":"failed","activeConnections":0}}""",
            response.body,
        )
    }
}
