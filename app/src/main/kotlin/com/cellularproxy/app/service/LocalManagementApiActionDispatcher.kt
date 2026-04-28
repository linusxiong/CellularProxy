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
            method = "POST",
            url = "http://${config.proxy.listenHost.toLocalManagementHost()}:${config.proxy.listenPort}${action.path}",
            bearerToken = sensitiveConfig.managementApiToken,
        ),
    )
}

enum class LocalManagementApiAction(
    val path: String,
) {
    CloudflareStart("/api/cloudflare/start"),
    CloudflareStop("/api/cloudflare/stop"),
    RotateMobileData("/api/rotate/mobile-data"),
    RotateAirplaneMode("/api/rotate/airplane-mode"),
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
