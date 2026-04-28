package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.shared.config.AppConfig

internal fun localManagementApiProbeResultFrom(
    request: () -> LocalManagementApiActionResponse,
): LocalManagementApiProbeResult = runCatching(request)
    .fold(
        onSuccess = { response -> response.statusCode.toLocalManagementApiProbeResult() },
        onFailure = { LocalManagementApiProbeResult.Unavailable },
    )

internal fun localManagementApiProbeResultFromSensitiveConfigLoadResult(
    result: SensitiveConfigLoadResult,
    request: (SensitiveConfig) -> LocalManagementApiActionResponse,
): LocalManagementApiProbeResult = when (result) {
    is SensitiveConfigLoadResult.Loaded ->
        localManagementApiProbeResultFrom {
            request(result.config)
        }
    SensitiveConfigLoadResult.MissingRequiredSecrets,
    is SensitiveConfigLoadResult.Invalid,
    -> LocalManagementApiProbeResult.Unavailable
}

internal fun cloudflareManagementApiProbeResultFrom(
    config: AppConfig,
    tunnelTokenPresent: Boolean,
    request: () -> LocalManagementApiActionResponse,
): CloudflareManagementApiProbeResult {
    if (!config.cloudflare.enabled ||
        !tunnelTokenPresent ||
        config.cloudflare.managementHostnameLabel.isNullOrBlank()
    ) {
        return CloudflareManagementApiProbeResult.NotConfigured
    }

    return runCatching(request)
        .fold(
            onSuccess = { response -> response.statusCode.toCloudflareManagementApiProbeResult() },
            onFailure = { CloudflareManagementApiProbeResult.Error },
        )
}

internal fun cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
    config: AppConfig,
    result: SensitiveConfigLoadResult,
    request: (SensitiveConfig) -> LocalManagementApiActionResponse,
): CloudflareManagementApiProbeResult = when (result) {
    is SensitiveConfigLoadResult.Loaded ->
        cloudflareManagementApiProbeResultFrom(
            config = config,
            tunnelTokenPresent = result.config.cloudflareTunnelToken != null,
        ) {
            request(result.config)
        }
    SensitiveConfigLoadResult.MissingRequiredSecrets ->
        cloudflareManagementApiProbeResultFrom(
            config = config,
            tunnelTokenPresent = false,
        ) {
            error("Cloudflare management API probe should not dispatch without required sensitive config")
        }
    is SensitiveConfigLoadResult.Invalid -> CloudflareManagementApiProbeResult.Error
}

private fun Int.toLocalManagementApiProbeResult(): LocalManagementApiProbeResult = when (this) {
    in HTTP_SUCCESS_STATUSES -> LocalManagementApiProbeResult.Authenticated
    HTTP_UNAUTHORIZED_STATUS -> LocalManagementApiProbeResult.Unauthorized
    HTTP_UNAVAILABLE_STATUS -> LocalManagementApiProbeResult.Unavailable
    else -> LocalManagementApiProbeResult.Error
}

private fun Int.toCloudflareManagementApiProbeResult(): CloudflareManagementApiProbeResult = when (this) {
    in HTTP_SUCCESS_STATUSES -> CloudflareManagementApiProbeResult.Authenticated
    HTTP_UNAUTHORIZED_STATUS -> CloudflareManagementApiProbeResult.Unauthorized
    HTTP_UNAVAILABLE_STATUS -> CloudflareManagementApiProbeResult.Unavailable
    else -> CloudflareManagementApiProbeResult.Error
}

private val HTTP_SUCCESS_STATUSES = 200..299
private const val HTTP_UNAUTHORIZED_STATUS = 401
private const val HTTP_UNAVAILABLE_STATUS = 503
