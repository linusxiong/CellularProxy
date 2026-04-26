package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential

data class ProxySettingsFormState(
    val listenHost: String,
    val listenPort: String,
    val authEnabled: Boolean,
    val route: RouteTarget,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
) {
    fun toAppConfig(base: AppConfig): ProxySettingsFormResult {
        return toSettings(
            base = base,
            sensitiveConfig = null,
        )
    }

    fun toSettings(
        base: AppConfig,
        sensitiveConfig: SensitiveConfig?,
    ): ProxySettingsFormResult {
        val normalizedPort = listenPort.trim()
        val parsedPort = normalizedPort.toStrictPortOrNull()
        val candidate = base.copy(
            proxy = ProxyConfig(
                listenHost = listenHost.trim(),
                listenPort = parsedPort ?: INVALID_PORT_SENTINEL,
                authEnabled = authEnabled,
            ),
            network = base.network.copy(
                defaultRoutePolicy = route,
            ),
        )
        val errors = buildList {
            if (candidate.validate().errors.contains(ConfigValidationError.InvalidListenHost)) {
                add(ConfigValidationError.InvalidListenHost)
            }
            if (parsedPort == null) {
                add(ConfigValidationError.InvalidListenPort)
            }
        }
        if (errors.isNotEmpty()) {
            return ProxySettingsFormResult.Invalid(errors)
        }

        val updatedSensitiveConfig = sensitiveConfig?.withProxyCredentialEdit(
            username = proxyUsername,
            password = proxyPassword,
        ) ?: ProxyCredentialEditResult.Valid(null)
        when (updatedSensitiveConfig) {
            ProxyCredentialEditResult.Invalid -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidProxyCredential = true,
                )
            }
            is ProxyCredentialEditResult.Valid -> Unit
        }

        val warnings = buildSet {
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
                route = config.network.defaultRoutePolicy,
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

        val result = form.toSettings(
            base = baseConfig,
            sensitiveConfig = loadSensitiveConfig?.invoke(),
        )
        return when (result) {
            is ProxySettingsFormResult.Invalid -> ProxySettingsSaveResult.Invalid(
                errors = result.errors,
                invalidProxyCredential = result.invalidProxyCredential,
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
    ) : ProxySettingsSaveResult
}

private sealed interface ProxyCredentialEditResult {
    data class Valid(val value: SensitiveConfig?) : ProxyCredentialEditResult
    data object Invalid : ProxyCredentialEditResult
}

private fun SensitiveConfig.withProxyCredentialEdit(
    username: String,
    password: String,
): ProxyCredentialEditResult {
    if (username.isEmpty() && password.isEmpty()) {
        return ProxyCredentialEditResult.Valid(this)
    }
    if (username.isEmpty() || password.isEmpty()) {
        return ProxyCredentialEditResult.Invalid
    }

    val credential = runCatching {
        ProxyCredential(
            username = username,
            password = password,
        )
    }.getOrNull() ?: return ProxyCredentialEditResult.Invalid

    return ProxyCredentialEditResult.Valid(
        copy(proxyCredential = credential),
    )
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

private fun String.toStrictPortOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it in TCP_PORT_RANGE }
}

private val TCP_PORT_RANGE = 1..65_535
private const val INVALID_PORT_SENTINEL = 0
