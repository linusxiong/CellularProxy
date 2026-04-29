package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.diagnostics.PublicIpDiagnosticsProbeResult
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.network.PublicIpProbeResult
import com.cellularproxy.shared.config.AppConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

internal fun publicIpDiagnosticsProbeResultFrom(
    request: () -> LocalManagementApiActionResponse,
): PublicIpDiagnosticsProbeResult = runCatching {
    val response = request()
    if (!response.isSuccessful) {
        return@runCatching PublicIpDiagnosticsProbeResult.Unavailable
    }
    Json
        .parseToJsonElement(response.body)
        .jsonObject
        .nullableStringValue("publicIp")
        ?.let(PublicIpDiagnosticsProbeResult::Observed)
        ?: PublicIpDiagnosticsProbeResult.Unavailable
}.getOrDefault(PublicIpDiagnosticsProbeResult.Unavailable)

internal fun publicIpDiagnosticsProbeResultFromSensitiveConfigLoadResult(
    result: SensitiveConfigLoadResult,
    request: (SensitiveConfig) -> LocalManagementApiActionResponse,
): PublicIpDiagnosticsProbeResult = when (result) {
    is SensitiveConfigLoadResult.Loaded ->
        publicIpDiagnosticsProbeResultFrom {
            request(result.config)
        }
    SensitiveConfigLoadResult.MissingRequiredSecrets,
    is SensitiveConfigLoadResult.Invalid,
    -> PublicIpDiagnosticsProbeResult.Unavailable
}

internal fun publicIpDiagnosticsProbeResultFromPublicIpProbeResult(
    result: PublicIpProbeResult,
): PublicIpDiagnosticsProbeResult = when (result) {
    is PublicIpProbeResult.Success -> PublicIpDiagnosticsProbeResult.Observed(result.publicIp)
    is PublicIpProbeResult.Failed -> PublicIpDiagnosticsProbeResult.Unavailable
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

private fun JsonObject.nullableStringValue(name: String): String? = nullableElementValue(name)
    ?.jsonPrimitive
    ?.content

private fun JsonObject.nullableElementValue(name: String): JsonElement? = get(name)
    ?.takeUnless { element -> element is JsonNull }

private val HTTP_SUCCESS_STATUSES = 200..299
private const val HTTP_UNAUTHORIZED_STATUS = 401
private const val HTTP_UNAVAILABLE_STATUS = 503
