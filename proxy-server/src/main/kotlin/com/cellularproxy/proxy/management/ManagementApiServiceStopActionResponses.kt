package com.cellularproxy.proxy.management

import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionDisposition
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult

object ManagementApiServiceStopActionResponses {
    fun transition(result: ProxyServiceStopTransitionResult): ManagementApiResponse = ManagementApiResponse.json(
        statusCode = if (result.accepted) 202 else 409,
        body =
            buildString {
                append('{')
                append(""""accepted":""")
                append(result.accepted)
                append(',')
                append(""""disposition":""")
                append(result.disposition.apiValue().jsonString())
                append(',')
                append(""""service":""")
                append(result.status.serviceStopJson())
                append('}')
            },
    )
}

private fun ProxyServiceStatus.serviceStopJson(): String = buildString {
    append('{')
    append(""""state":""")
    append(state.apiValue().jsonString())
    append(',')
    append(""""activeConnections":""")
    append(metrics.activeConnections)
    append('}')
}

private fun ProxyServiceStopTransitionDisposition.apiValue(): String = when (this) {
    ProxyServiceStopTransitionDisposition.Accepted -> "accepted"
    ProxyServiceStopTransitionDisposition.Duplicate -> "duplicate"
    ProxyServiceStopTransitionDisposition.Ignored -> "ignored"
}

private fun ProxyServiceState.apiValue(): String = when (this) {
    ProxyServiceState.Starting -> "starting"
    ProxyServiceState.Running -> "running"
    ProxyServiceState.Stopping -> "stopping"
    ProxyServiceState.Stopped -> "stopped"
    ProxyServiceState.Failed -> "failed"
}
