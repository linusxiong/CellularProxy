package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URI

class LocalManagementApiStatusReader(
    private val transport: (LocalManagementApiActionRequest) -> LocalManagementApiStatusResponse =
        ::sendStatusHttpRequest,
) {
    fun load(
        config: AppConfig,
        sensitiveConfig: SensitiveConfig,
    ): ProxyServiceStatus? = loadSnapshot(config, sensitiveConfig)?.proxyStatus

    fun loadSnapshot(
        config: AppConfig,
        sensitiveConfig: SensitiveConfig,
    ): LocalManagementApiRuntimeSnapshot? = runCatching {
        val response =
            transport(
                LocalManagementApiActionRequest(
                    method = LocalManagementApiAction.RootStatus.method,
                    url = LocalManagementApiAction.RootStatus.url(config),
                    bearerToken = sensitiveConfig.managementApiToken,
                ),
            )
        if (response.isSuccessful) {
            localManagementApiRuntimeSnapshotFromStatusJson(response.body)
        } else {
            null
        }
    }.getOrNull()
}

data class LocalManagementApiRuntimeSnapshot(
    val proxyStatus: ProxyServiceStatus,
    val rotationStatus: RotationStatus,
    val rotationCooldownRemainingSeconds: Long? = null,
    val cloudflareEdgeSessionSummary: String? = null,
)

data class LocalManagementApiStatusResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

internal fun proxyServiceStatusFromManagementApiStatusJson(body: String): ProxyServiceStatus? = runCatching {
    localManagementApiRuntimeSnapshotFromStatusJson(body)?.proxyStatus
}.getOrNull()

internal fun localManagementApiRuntimeSnapshotFromStatusJson(body: String): LocalManagementApiRuntimeSnapshot? = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val service = root.objectValue("service")
    val rotation = root.objectValue("rotation")
    val cloudflare = root.objectValue("cloudflare")
    LocalManagementApiRuntimeSnapshot(
        proxyStatus =
            ProxyServiceStatus(
                state = service.stringValue("state").toProxyServiceState(),
                listenHost = service.nullableStringValue("listenHost"),
                listenPort = service.nullableIntValue("listenPort"),
                configuredRoute = service.stringValue("configuredRoute").toRouteTarget(),
                boundRoute = service.nullableObjectValue("boundRoute")?.toNetworkDescriptor(),
                publicIp = service.nullableStringValue("publicIp"),
                hasHighSecurityRisk = service.booleanValue("highSecurityRisk"),
                startupError = service.nullableStringValue("startupError")?.toProxyStartupError(),
                metrics = root.objectValue("metrics").toProxyTrafficMetrics(),
                cloudflare = cloudflare.toCloudflareTunnelStatus(),
                rootAvailability = root.objectValue("root").stringValue("availability").toRootAvailabilityStatus(),
            ),
        rotationStatus = rotation.toRotationStatus(),
        rotationCooldownRemainingSeconds =
            rotation
                .nullableLongValue("cooldownRemainingMillis")
                ?.toCeilingSeconds(),
        cloudflareEdgeSessionSummary = cloudflare.nullableStringValue("edgeSessionSummary"),
    )
}.getOrNull()

private fun JsonObject.toProxyTrafficMetrics(): ProxyTrafficMetrics = ProxyTrafficMetrics(
    activeConnections = longValue("activeConnections"),
    totalConnections = longValue("totalConnections"),
    rejectedConnections = longValue("rejectedConnections"),
    bytesReceived = longValue("bytesReceived"),
    bytesSent = longValue("bytesSent"),
)

private fun JsonObject.toCloudflareTunnelStatus(): CloudflareTunnelStatus {
    val state = stringValue("state").toCloudflareTunnelState()
    val failureReason = nullableStringValue("failureReason")
    return when (state) {
        CloudflareTunnelState.Disabled -> CloudflareTunnelStatus.disabled()
        CloudflareTunnelState.Starting -> CloudflareTunnelStatus.starting()
        CloudflareTunnelState.Connected -> CloudflareTunnelStatus.connected()
        CloudflareTunnelState.Degraded -> CloudflareTunnelStatus.degraded()
        CloudflareTunnelState.Stopped -> CloudflareTunnelStatus.stopped()
        CloudflareTunnelState.Failed -> CloudflareTunnelStatus.failed(requireNotNull(failureReason))
    }
}

private fun JsonObject.toNetworkDescriptor(): NetworkDescriptor = NetworkDescriptor(
    id = stringValue("id"),
    category = stringValue("category").toNetworkCategory(),
    displayName = stringValue("displayName"),
    isAvailable = booleanValue("available"),
)

private fun JsonObject.toRotationStatus(): RotationStatus = RotationStatus(
    state = stringValue("state").toRotationState(),
    operation = nullableStringValue("operation")?.toRotationOperation(),
    oldPublicIp = nullableStringValue("oldPublicIp"),
    newPublicIp = nullableStringValue("newPublicIp"),
    publicIpChanged = nullableBooleanValue("publicIpChanged"),
    failureReason = nullableStringValue("failureReason")?.toRotationFailureReason(),
)

private fun String.toProxyServiceState(): ProxyServiceState = when (this) {
    "starting" -> ProxyServiceState.Starting
    "running" -> ProxyServiceState.Running
    "stopping" -> ProxyServiceState.Stopping
    "stopped" -> ProxyServiceState.Stopped
    "failed" -> ProxyServiceState.Failed
    else -> error("Unknown proxy service state: $this")
}

private fun String.toRouteTarget(): RouteTarget = when (this) {
    "wifi" -> RouteTarget.WiFi
    "cellular" -> RouteTarget.Cellular
    "vpn" -> RouteTarget.Vpn
    "automatic" -> RouteTarget.Automatic
    else -> error("Unknown route target: $this")
}

private fun String.toNetworkCategory(): NetworkCategory = when (this) {
    "wifi" -> NetworkCategory.WiFi
    "cellular" -> NetworkCategory.Cellular
    "vpn" -> NetworkCategory.Vpn
    else -> error("Unknown network category: $this")
}

private fun String.toCloudflareTunnelState(): CloudflareTunnelState = when (this) {
    "disabled" -> CloudflareTunnelState.Disabled
    "starting" -> CloudflareTunnelState.Starting
    "connected" -> CloudflareTunnelState.Connected
    "degraded" -> CloudflareTunnelState.Degraded
    "stopped" -> CloudflareTunnelState.Stopped
    "failed" -> CloudflareTunnelState.Failed
    else -> error("Unknown Cloudflare tunnel state: $this")
}

private fun String.toRootAvailabilityStatus(): RootAvailabilityStatus = when (this) {
    "unknown" -> RootAvailabilityStatus.Unknown
    "available" -> RootAvailabilityStatus.Available
    "unavailable" -> RootAvailabilityStatus.Unavailable
    else -> error("Unknown root availability: $this")
}

private fun String.toProxyStartupError(): ProxyStartupError = when (this) {
    "invalid_listen_address" -> ProxyStartupError.InvalidListenAddress
    "invalid_listen_port" -> ProxyStartupError.InvalidListenPort
    "invalid_max_concurrent_connections" -> ProxyStartupError.InvalidMaxConcurrentConnections
    "port_already_in_use" -> ProxyStartupError.PortAlreadyInUse
    "missing_management_api_token" -> ProxyStartupError.MissingManagementApiToken
    "unavailable_selected_route" -> ProxyStartupError.UnavailableSelectedRoute
    "missing_cloudflare_tunnel_token" -> ProxyStartupError.MissingCloudflareTunnelToken
    else -> error("Unknown proxy startup error: $this")
}

private fun String.toRotationState(): RotationState = when (this) {
    "idle" -> RotationState.Idle
    "checking_cooldown" -> RotationState.CheckingCooldown
    "checking_root" -> RotationState.CheckingRoot
    "probing_old_public_ip" -> RotationState.ProbingOldPublicIp
    "pausing_new_requests" -> RotationState.PausingNewRequests
    "draining_connections" -> RotationState.DrainingConnections
    "running_disable_command" -> RotationState.RunningDisableCommand
    "waiting_for_toggle_delay" -> RotationState.WaitingForToggleDelay
    "running_enable_command" -> RotationState.RunningEnableCommand
    "waiting_for_network_return" -> RotationState.WaitingForNetworkReturn
    "probing_new_public_ip" -> RotationState.ProbingNewPublicIp
    "resuming_proxy_requests" -> RotationState.ResumingProxyRequests
    "completed" -> RotationState.Completed
    "failed" -> RotationState.Failed
    else -> error("Unknown rotation state: $this")
}

private fun String.toRotationOperation(): RotationOperation = when (this) {
    "mobile_data" -> RotationOperation.MobileData
    "airplane_mode" -> RotationOperation.AirplaneMode
    else -> error("Unknown rotation operation: $this")
}

private fun String.toRotationFailureReason(): RotationFailureReason = when (this) {
    "cooldown_active" -> RotationFailureReason.CooldownActive
    "root_unavailable" -> RotationFailureReason.RootUnavailable
    "old_public_ip_probe_failed" -> RotationFailureReason.OldPublicIpProbeFailed
    "root_command_failed" -> RotationFailureReason.RootCommandFailed
    "root_command_timed_out" -> RotationFailureReason.RootCommandTimedOut
    "network_return_timed_out" -> RotationFailureReason.NetworkReturnTimedOut
    "new_public_ip_probe_failed" -> RotationFailureReason.NewPublicIpProbeFailed
    "strict_ip_change_required" -> RotationFailureReason.StrictIpChangeRequired
    "root_operations_disabled" -> RotationFailureReason.RootOperationsDisabled
    "execution_unavailable" -> RotationFailureReason.ExecutionUnavailable
    else -> error("Unknown rotation failure reason: $this")
}

private fun JsonObject.objectValue(name: String): JsonObject = requireNotNull(nullableObjectValue(name))

private fun JsonObject.nullableObjectValue(name: String): JsonObject? = nullableElementValue(name)?.takeUnless(JsonElement::isJsonNull)?.jsonObject

private fun JsonObject.stringValue(name: String): String = requireNotNull(nullableStringValue(name))

private fun JsonObject.nullableStringValue(name: String): String? = nullableElementValue(name)?.takeUnless(JsonElement::isJsonNull)?.jsonPrimitive?.content

private fun JsonObject.booleanValue(name: String): Boolean = requireNotNull(nullableElementValue(name)?.jsonPrimitive?.booleanOrNull)

private fun JsonObject.nullableBooleanValue(name: String): Boolean? = nullableElementValue(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.jsonPrimitive
    ?.booleanOrNull

private fun JsonObject.longValue(name: String): Long = requireNotNull(nullableElementValue(name)?.jsonPrimitive?.longOrNull)

private fun JsonObject.nullableLongValue(name: String): Long? = nullableElementValue(name)?.takeUnless(JsonElement::isJsonNull)?.jsonPrimitive?.longOrNull

private fun Long.toCeilingSeconds(): Long = (this + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

private fun JsonObject.nullableIntValue(name: String): Int? = nullableElementValue(name)?.takeUnless(JsonElement::isJsonNull)?.jsonPrimitive?.intOrNull

private fun JsonObject.nullableElementValue(name: String): JsonElement? = this[name]

private val JsonElement.isJsonNull: Boolean
    get() = this is JsonNull

private fun sendStatusHttpRequest(request: LocalManagementApiActionRequest): LocalManagementApiStatusResponse {
    val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = request.method
        connection.connectTimeout = LOCAL_MANAGEMENT_API_STATUS_TIMEOUT_MILLIS
        connection.readTimeout = LOCAL_MANAGEMENT_API_STATUS_TIMEOUT_MILLIS
        connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
        connection.doOutput = false
        LocalManagementApiStatusResponse(
            statusCode = connection.responseCode,
            body =
                runCatching {
                    val stream =
                        if (connection.responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream ?: connection.inputStream
                        }
                    stream.bufferedReader().use { reader -> reader.readText() }
                }.getOrDefault(""),
        )
    } finally {
        connection.disconnect()
    }
}

private const val LOCAL_MANAGEMENT_API_STATUS_TIMEOUT_MILLIS = 5_000
private const val MILLIS_PER_SECOND = 1_000L
