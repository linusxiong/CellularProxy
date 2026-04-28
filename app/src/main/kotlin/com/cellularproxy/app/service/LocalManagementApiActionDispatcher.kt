package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.config.AppConfig
import java.net.HttpURLConnection
import java.net.URI

class LocalManagementApiActionDispatcher(
    private val transport: (LocalManagementApiActionRequest) -> LocalManagementApiActionResponse =
        ::sendHttpRequest,
) {
    fun dispatch(
        action: LocalManagementApiAction,
        config: AppConfig,
        sensitiveConfig: SensitiveConfig,
    ): LocalManagementApiActionResponse = transport(
        LocalManagementApiActionRequest(
            method = action.method,
            url = action.url(config),
            bearerToken = sensitiveConfig.managementApiToken,
        ),
    )
}

enum class LocalManagementApiAction(
    val method: String = "POST",
    val path: String,
    private val target: LocalManagementApiActionTarget = LocalManagementApiActionTarget.Local,
) {
    CloudflareStart(path = "/api/cloudflare/start"),
    CloudflareStop(path = "/api/cloudflare/stop"),
    CloudflareReconnect(path = "/api/cloudflare/reconnect"),
    CloudflareManagementStatus(
        method = "GET",
        path = "/api/status",
        target = LocalManagementApiActionTarget.CloudflareManagementHostname,
    ),
    RotateMobileData(path = "/api/rotate/mobile-data"),
    RotateAirplaneMode(path = "/api/rotate/airplane-mode"),
    ;

    fun url(config: AppConfig): String = when (target) {
        LocalManagementApiActionTarget.Local ->
            "http://${config.proxy.listenHost.toLocalManagementHost()}:${config.proxy.listenPort}$path"
        LocalManagementApiActionTarget.CloudflareManagementHostname ->
            config.cloudflare.managementHostnameLabel.toCloudflareManagementUrl(path)
    }
}

private enum class LocalManagementApiActionTarget {
    Local,
    CloudflareManagementHostname,
}

data class LocalManagementApiActionRequest(
    val method: String,
    val url: String,
    val bearerToken: String,
)

data class LocalManagementApiActionResponse(
    val statusCode: Int,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

private fun String.toLocalManagementHost(): String = when (this) {
    "0.0.0.0" -> "127.0.0.1"
    else -> this
}

private fun String?.toCloudflareManagementUrl(path: String): String {
    val label =
        requireNotNull(this?.trim()?.takeIf(String::isNotEmpty)) {
            "Cloudflare management hostname label is required"
        }
    val uri = URI(if ("://" in label) label else "https://$label")
    val scheme = requireNotNull(uri.scheme?.lowercase()) { "Cloudflare management hostname requires a scheme" }
    require(scheme == "http" || scheme == "https") { "Cloudflare management hostname must use HTTP or HTTPS" }
    val authority = requireNotNull(uri.rawAuthority) { "Cloudflare management hostname requires a host" }
    return URI(scheme, authority, path, null, null).toString()
}

private fun sendHttpRequest(request: LocalManagementApiActionRequest): LocalManagementApiActionResponse {
    val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = request.method
        connection.connectTimeout = LOCAL_MANAGEMENT_API_ACTION_TIMEOUT_MILLIS
        connection.readTimeout = LOCAL_MANAGEMENT_API_ACTION_TIMEOUT_MILLIS
        connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
        connection.doOutput = false
        LocalManagementApiActionResponse(statusCode = connection.responseCode)
    } finally {
        connection.disconnect()
    }
}

private const val LOCAL_MANAGEMENT_API_ACTION_TIMEOUT_MILLIS = 5_000
