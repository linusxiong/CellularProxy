package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.cloudflare.CloudflareTunnelToken
import com.cellularproxy.cloudflare.CloudflareTunnelTokenParseResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ProxySettingsFormState(
    val listenHost: String,
    val listenPort: String,
    val authEnabled: Boolean,
    val maxConcurrentConnections: String = "64",
    val route: RouteTarget,
    val strictIpChangeRequired: Boolean = false,
    val mobileDataOffDelaySeconds: String = "3",
    val networkReturnTimeoutSeconds: String = "60",
    val cooldownSeconds: String = "180",
    val rootOperationsEnabled: Boolean = false,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val managementApiToken: String = "",
    val cloudflareEnabled: Boolean = false,
    val cloudflareTunnelToken: String = "",
    val cloudflareHostnameLabel: String = "",
) {
    fun toAppConfig(base: AppConfig): ProxySettingsFormResult =
        toSettings(
            base = base,
            sensitiveConfig = null,
        )

    fun toSettings(
        base: AppConfig,
        sensitiveConfig: SensitiveConfig?,
    ): ProxySettingsFormResult {
        val normalizedPort = listenPort.trim()
        val parsedPort = normalizedPort.toStrictPortOrNull()
        val parsedMaxConcurrentConnections = maxConcurrentConnections.trim().toStrictPositiveIntOrNull()
        val parsedMobileDataOffDelay = mobileDataOffDelaySeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val parsedNetworkReturnTimeout = networkReturnTimeoutSeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val parsedCooldown = cooldownSeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val proxyAndRouteCandidate =
            base.copy(
                proxy =
                    ProxyConfig(
                        listenHost = listenHost.trim(),
                        listenPort = parsedPort ?: INVALID_PORT_SENTINEL,
                        authEnabled = authEnabled,
                        maxConcurrentConnections = parsedMaxConcurrentConnections ?: INVALID_POSITIVE_INT_SENTINEL,
                    ),
                network =
                    base.network.copy(
                        defaultRoutePolicy = route,
                    ),
                rotation =
                    base.rotation.copy(
                        strictIpChangeRequired = strictIpChangeRequired,
                        mobileDataOffDelay = parsedMobileDataOffDelay ?: base.rotation.mobileDataOffDelay,
                        networkReturnTimeout = parsedNetworkReturnTimeout ?: base.rotation.networkReturnTimeout,
                        cooldown = parsedCooldown ?: base.rotation.cooldown,
                    ),
                root =
                    base.root.copy(
                        operationsEnabled = rootOperationsEnabled,
                    ),
            )
        val errors =
            buildList {
                if (proxyAndRouteCandidate.validate().errors.contains(ConfigValidationError.InvalidListenHost)) {
                    add(ConfigValidationError.InvalidListenHost)
                }
                if (parsedPort == null) {
                    add(ConfigValidationError.InvalidListenPort)
                }
            }
        if (errors.isNotEmpty()) {
            return ProxySettingsFormResult.Invalid(errors)
        }
        if (parsedMaxConcurrentConnections == null) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidMaxConcurrentConnections = true,
            )
        }
        if (
            parsedMobileDataOffDelay == null ||
            parsedNetworkReturnTimeout == null ||
            parsedCooldown == null
        ) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidRotationTiming = true,
            )
        }

        val updatedSensitiveConfig =
            sensitiveConfig
                ?.withProxyCredentialEdit(
                    username = proxyUsername,
                    password = proxyPassword,
                )?.withManagementApiTokenEdit(managementApiToken)
                ?.withCloudflareTunnelTokenEdit(cloudflareTunnelToken)
                ?: SensitiveConfigEditResult.Valid(null)
        when (updatedSensitiveConfig) {
            SensitiveConfigEditResult.InvalidProxyCredential -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidProxyCredential = true,
                )
            }
            SensitiveConfigEditResult.InvalidManagementApiToken -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidManagementApiToken = true,
                )
            }
            SensitiveConfigEditResult.InvalidCloudflareTunnelToken -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidCloudflareTunnelToken = true,
                )
            }
            is SensitiveConfigEditResult.Valid -> Unit
        }
        val appliesSensitiveEdits = sensitiveConfig != null
        val tunnelTokenPresent =
            if (appliesSensitiveEdits) {
                updatedSensitiveConfig.value?.cloudflareTunnelToken?.isNotBlank() == true
            } else {
                base.cloudflare.tunnelTokenPresent
            }
        if (appliesSensitiveEdits && cloudflareEnabled && !tunnelTokenPresent) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidCloudflareTunnelToken = true,
            )
        }
        val candidate =
            proxyAndRouteCandidate.copy(
                cloudflare =
                    if (appliesSensitiveEdits) {
                        base.cloudflare.copy(
                            enabled = cloudflareEnabled,
                            tunnelTokenPresent = tunnelTokenPresent,
                            managementHostnameLabel = cloudflareHostnameLabel.trim().takeIf(String::isNotEmpty),
                        )
                    } else {
                        base.cloudflare
                    },
            )

        val warnings =
            buildSet {
                if (candidate.proxy.hasHighSecurityRisk) {
                    add(ProxySettingsFormWarning.BroadUnauthenticatedProxy)
                }
            }
        return ProxySettingsFormResult.Valid(
            config = candidate,
            warnings = warnings,
            sensitiveConfig = updatedSensitiveConfig.value,
        )
    }

    companion object {
        fun from(config: AppConfig): ProxySettingsFormState =
            ProxySettingsFormState(
                listenHost = config.proxy.listenHost,
                listenPort = config.proxy.listenPort.toString(),
                authEnabled = config.proxy.authEnabled,
                maxConcurrentConnections = config.proxy.maxConcurrentConnections.toString(),
                route = config.network.defaultRoutePolicy,
                strictIpChangeRequired = config.rotation.strictIpChangeRequired,
                mobileDataOffDelaySeconds =
                    config.rotation.mobileDataOffDelay.inWholeSeconds
                        .toString(),
                networkReturnTimeoutSeconds =
                    config.rotation.networkReturnTimeout.inWholeSeconds
                        .toString(),
                cooldownSeconds =
                    config.rotation.cooldown.inWholeSeconds
                        .toString(),
                rootOperationsEnabled = config.root.operationsEnabled,
                cloudflareEnabled = config.cloudflare.enabled,
                cloudflareHostnameLabel = config.cloudflare.managementHostnameLabel.orEmpty(),
            )
    }
}

sealed interface ProxySettingsFormResult {
    data class Valid(
        val config: AppConfig,
        val warnings: Set<ProxySettingsFormWarning>,
        val sensitiveConfig: SensitiveConfig? = null,
    ) : ProxySettingsFormResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
        val invalidProxyCredential: Boolean = false,
        val invalidManagementApiToken: Boolean = false,
        val invalidCloudflareTunnelToken: Boolean = false,
        val invalidMaxConcurrentConnections: Boolean = false,
        val invalidRotationTiming: Boolean = false,
    ) : ProxySettingsFormResult
}

enum class ProxySettingsFormWarning {
    BroadUnauthenticatedProxy,
}

class ProxySettingsFormController(
    private val loadConfig: () -> AppConfig,
    private val saveConfig: (AppConfig) -> Unit,
    private val loadSensitiveConfig: (() -> SensitiveConfig)? = null,
    private val saveSensitiveConfig: ((SensitiveConfig) -> Unit)? = null,
) {
    fun save(form: ProxySettingsFormState): ProxySettingsSaveResult {
        val baseConfig = loadConfig()
        when (val plainResult = form.toAppConfig(base = baseConfig)) {
            is ProxySettingsFormResult.Invalid -> {
                return ProxySettingsSaveResult.Invalid(
                    errors = plainResult.errors,
                    invalidProxyCredential = plainResult.invalidProxyCredential,
                    invalidManagementApiToken = plainResult.invalidManagementApiToken,
                    invalidCloudflareTunnelToken = plainResult.invalidCloudflareTunnelToken,
                    invalidMaxConcurrentConnections = plainResult.invalidMaxConcurrentConnections,
                    invalidRotationTiming = plainResult.invalidRotationTiming,
                )
            }
            is ProxySettingsFormResult.Valid -> Unit
        }
        if (form.hasInvalidProxyCredentialEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidProxyCredential = true,
            )
        }
        if (form.hasInvalidManagementApiTokenEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidManagementApiToken = true,
            )
        }
        if (form.hasInvalidCloudflareTunnelTokenEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidCloudflareTunnelToken = true,
            )
        }

        val result =
            form.toSettings(
                base = baseConfig,
                sensitiveConfig = loadSensitiveConfig?.invoke(),
            )
        return when (result) {
            is ProxySettingsFormResult.Invalid ->
                ProxySettingsSaveResult.Invalid(
                    errors = result.errors,
                    invalidProxyCredential = result.invalidProxyCredential,
                    invalidManagementApiToken = result.invalidManagementApiToken,
                    invalidCloudflareTunnelToken = result.invalidCloudflareTunnelToken,
                    invalidMaxConcurrentConnections = result.invalidMaxConcurrentConnections,
                    invalidRotationTiming = result.invalidRotationTiming,
                )
            is ProxySettingsFormResult.Valid -> {
                result.sensitiveConfig?.let { sensitiveConfig ->
                    saveSensitiveConfig?.invoke(sensitiveConfig)
                        ?: error("Sensitive config save callback is required when sensitive config is loaded")
                }
                saveConfig(result.config)
                ProxySettingsSaveResult.Saved(
                    config = result.config,
                    warnings = result.warnings,
                    sensitiveConfig = result.sensitiveConfig,
                )
            }
        }
    }
}

sealed interface ProxySettingsSaveResult {
    data class Saved(
        val config: AppConfig,
        val warnings: Set<ProxySettingsFormWarning>,
        val sensitiveConfig: SensitiveConfig? = null,
    ) : ProxySettingsSaveResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
        val invalidProxyCredential: Boolean = false,
        val invalidManagementApiToken: Boolean = false,
        val invalidCloudflareTunnelToken: Boolean = false,
        val invalidMaxConcurrentConnections: Boolean = false,
        val invalidRotationTiming: Boolean = false,
    ) : ProxySettingsSaveResult
}

private sealed interface SensitiveConfigEditResult {
    data class Valid(
        val value: SensitiveConfig?,
    ) : SensitiveConfigEditResult

    data object InvalidProxyCredential : SensitiveConfigEditResult

    data object InvalidManagementApiToken : SensitiveConfigEditResult

    data object InvalidCloudflareTunnelToken : SensitiveConfigEditResult
}

private fun SensitiveConfig.withProxyCredentialEdit(
    username: String,
    password: String,
): SensitiveConfigEditResult {
    if (username.isEmpty() && password.isEmpty()) {
        return SensitiveConfigEditResult.Valid(this)
    }
    if (username.isEmpty() || password.isEmpty()) {
        return SensitiveConfigEditResult.InvalidProxyCredential
    }

    val credential =
        runCatching {
            ProxyCredential(
                username = username,
                password = password,
            )
        }.getOrNull() ?: return SensitiveConfigEditResult.InvalidProxyCredential

    return SensitiveConfigEditResult.Valid(
        copy(proxyCredential = credential),
    )
}

private fun SensitiveConfigEditResult.withManagementApiTokenEdit(managementApiToken: String): SensitiveConfigEditResult =
    when (this) {
        SensitiveConfigEditResult.InvalidProxyCredential,
        SensitiveConfigEditResult.InvalidManagementApiToken,
        SensitiveConfigEditResult.InvalidCloudflareTunnelToken,
        -> this
        is SensitiveConfigEditResult.Valid -> {
            if (managementApiToken.isEmpty()) {
                this
            } else if (managementApiToken.isBlank() || managementApiToken != managementApiToken.trim()) {
                SensitiveConfigEditResult.InvalidManagementApiToken
            } else {
                SensitiveConfigEditResult.Valid(value?.copy(managementApiToken = managementApiToken))
            }
        }
    }

private fun SensitiveConfigEditResult.withCloudflareTunnelTokenEdit(cloudflareTunnelToken: String): SensitiveConfigEditResult =
    when (this) {
        SensitiveConfigEditResult.InvalidProxyCredential,
        SensitiveConfigEditResult.InvalidManagementApiToken,
        SensitiveConfigEditResult.InvalidCloudflareTunnelToken,
        -> this
        is SensitiveConfigEditResult.Valid -> {
            if (cloudflareTunnelToken.isEmpty()) {
                this
            } else if (cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit()) {
                SensitiveConfigEditResult.InvalidCloudflareTunnelToken
            } else {
                SensitiveConfigEditResult.Valid(value?.copy(cloudflareTunnelToken = cloudflareTunnelToken))
            }
        }
    }

private fun ProxySettingsFormState.hasInvalidProxyCredentialEdit(): Boolean {
    if (proxyUsername.isEmpty() && proxyPassword.isEmpty()) {
        return false
    }
    if (proxyUsername.isEmpty() || proxyPassword.isEmpty()) {
        return true
    }

    return runCatching {
        ProxyCredential(
            username = proxyUsername,
            password = proxyPassword,
        )
    }.isFailure
}

private fun ProxySettingsFormState.hasInvalidManagementApiTokenEdit(): Boolean =
    managementApiToken.isNotEmpty() &&
        (managementApiToken.isBlank() || managementApiToken != managementApiToken.trim())

private fun ProxySettingsFormState.hasInvalidCloudflareTunnelTokenEdit(): Boolean =
    cloudflareTunnelToken.isNotEmpty() &&
        cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit()

private fun String.isInvalidCloudflareTunnelTokenEdit(): Boolean =
    isBlank() ||
        this != trim() ||
        CloudflareTunnelToken.parse(this) !is CloudflareTunnelTokenParseResult.Valid

private fun String.toStrictPortOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it in TCP_PORT_RANGE }
}

private fun String.toStrictPositiveIntOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it > 0 }
}

private fun String.toStrictPositiveSecondsDurationOrNull(): Duration? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toLongOrNull()
        ?.takeIf { it > 0 }
        ?.seconds
}

private val TCP_PORT_RANGE = 1..65_535
private const val INVALID_PORT_SENTINEL = 0
private const val INVALID_POSITIVE_INT_SENTINEL = 0
