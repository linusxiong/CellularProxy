package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.root.RootAvailabilityStatus

object ManagementApiReadOnlyResponses {
    fun health(status: ProxyServiceStatus): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = 200,
            body = """{"ok":true,"serviceState":${status.state.apiValue().jsonString()}}""",
        )

    fun status(
        status: ProxyServiceStatus,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = 200,
            body = buildString {
                append('{')
                append(""""service":""")
                append(status.serviceJson())
                append(',')
                append(""""metrics":""")
                append(status.metricsJson())
                append(',')
                append(""""cloudflare":""")
                append(status.cloudflare.cloudflareJson(secrets))
                append(',')
                append(""""root":{"availability":""")
                append(status.rootAvailability.apiValue().jsonString())
                append("}}")
            },
        )

    fun networks(networks: List<NetworkDescriptor>): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = 200,
            body = networks.joinToString(
                prefix = """{"networks":[""",
                postfix = "]}",
                separator = ",",
            ) { it.networkJson() },
        )

    fun publicIp(publicIp: String?): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = 200,
            body = """{"publicIp":${publicIp.jsonNullableString()}}""",
        )

    fun cloudflareStatus(
        status: CloudflareTunnelStatus,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = 200,
            body = """{"cloudflare":${status.cloudflareJson(secrets)}}""",
        )
}

private fun ProxyServiceStatus.serviceJson(): String =
    buildString {
        append('{')
        append(""""state":""")
        append(state.apiValue().jsonString())
        append(',')
        append(""""listenHost":""")
        append(listenHost.jsonNullableString())
        append(',')
        append(""""listenPort":""")
        append(listenPort?.toString() ?: "null")
        append(',')
        append(""""configuredRoute":""")
        append(configuredRoute.apiValue().jsonString())
        append(',')
        append(""""boundRoute":""")
        append(boundRoute?.networkJson() ?: "null")
        append(',')
        append(""""publicIp":""")
        append(publicIp.jsonNullableString())
        append(',')
        append(""""highSecurityRisk":""")
        append(hasHighSecurityRisk)
        append(',')
        append(""""startupError":""")
        append(startupError?.apiValue().jsonNullableString() ?: "null")
        append('}')
    }

private fun ProxyServiceStatus.metricsJson(): String =
    "{" +
        """"activeConnections":${metrics.activeConnections},""" +
        """"totalConnections":${metrics.totalConnections},""" +
        """"rejectedConnections":${metrics.rejectedConnections},""" +
        """"bytesReceived":${metrics.bytesReceived},""" +
        """"bytesSent":${metrics.bytesSent}""" +
        "}"

private fun CloudflareTunnelStatus.cloudflareJson(secrets: LogRedactionSecrets): String =
    buildString {
        append('{')
        append(""""state":""")
        append(state.apiValue().jsonString())
        append(',')
        append(""""remoteManagementAvailable":""")
        append(isRemoteManagementAvailable)
        append(',')
        append(""""failureReason":""")
        append(failureReason?.let { LogRedactor.redact(it, secrets) }.jsonNullableString())
        append('}')
    }

private fun NetworkDescriptor.networkJson(): String =
    buildString {
        append('{')
        append(""""id":""")
        append(id.jsonString())
        append(',')
        append(""""category":""")
        append(category.apiValue().jsonString())
        append(',')
        append(""""displayName":""")
        append(displayName.jsonString())
        append(',')
        append(""""available":""")
        append(isAvailable)
        append('}')
    }

private fun ProxyServiceState.apiValue(): String =
    when (this) {
        ProxyServiceState.Starting -> "starting"
        ProxyServiceState.Running -> "running"
        ProxyServiceState.Stopping -> "stopping"
        ProxyServiceState.Stopped -> "stopped"
        ProxyServiceState.Failed -> "failed"
    }

private fun RouteTarget.apiValue(): String =
    when (this) {
        RouteTarget.WiFi -> "wifi"
        RouteTarget.Cellular -> "cellular"
        RouteTarget.Vpn -> "vpn"
        RouteTarget.Automatic -> "automatic"
    }

private fun NetworkCategory.apiValue(): String =
    when (this) {
        NetworkCategory.WiFi -> "wifi"
        NetworkCategory.Cellular -> "cellular"
        NetworkCategory.Vpn -> "vpn"
    }

private fun CloudflareTunnelState.apiValue(): String =
    when (this) {
        CloudflareTunnelState.Disabled -> "disabled"
        CloudflareTunnelState.Starting -> "starting"
        CloudflareTunnelState.Connected -> "connected"
        CloudflareTunnelState.Degraded -> "degraded"
        CloudflareTunnelState.Stopped -> "stopped"
        CloudflareTunnelState.Failed -> "failed"
    }

private fun RootAvailabilityStatus.apiValue(): String =
    when (this) {
        RootAvailabilityStatus.Unknown -> "unknown"
        RootAvailabilityStatus.Available -> "available"
        RootAvailabilityStatus.Unavailable -> "unavailable"
    }

private fun ProxyStartupError.apiValue(): String =
    when (this) {
        ProxyStartupError.InvalidListenAddress -> "invalid_listen_address"
        ProxyStartupError.InvalidListenPort -> "invalid_listen_port"
        ProxyStartupError.PortAlreadyInUse -> "port_already_in_use"
        ProxyStartupError.MissingManagementApiToken -> "missing_management_api_token"
        ProxyStartupError.UnavailableSelectedRoute -> "unavailable_selected_route"
        ProxyStartupError.MissingCloudflareTunnelToken -> "missing_cloudflare_tunnel_token"
    }

private fun String?.jsonNullableString(): String =
    this?.jsonString() ?: "null"

private fun String.jsonString(): String =
    buildString {
        append('"')
        this@jsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < JSON_CONTROL_CHAR_LIMIT) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

private const val JSON_CONTROL_CHAR_LIMIT = 0x20
