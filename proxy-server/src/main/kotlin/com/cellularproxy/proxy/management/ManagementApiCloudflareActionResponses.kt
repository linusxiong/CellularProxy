package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.logging.LogRedactionSecrets

object ManagementApiCloudflareActionResponses {
    fun transition(
        result: CloudflareTunnelTransitionResult,
        secrets: LogRedactionSecrets,
    ): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = if (result.accepted) 202 else 409,
            body = buildString {
                append('{')
                append(""""accepted":""")
                append(result.accepted)
                append(',')
                append(""""disposition":""")
                append('"')
                append(result.disposition.apiValue())
                append('"')
                append(',')
                append(""""cloudflare":""")
                append(result.status.managementApiJson(secrets))
                append('}')
            },
        )
}

private fun com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition.apiValue(): String =
    when (this) {
        com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition.Accepted -> "accepted"
        com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition.Duplicate -> "duplicate"
        com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition.Ignored -> "ignored"
    }
