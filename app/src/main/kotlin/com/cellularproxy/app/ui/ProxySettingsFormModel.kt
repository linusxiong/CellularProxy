package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RouteTarget

data class ProxySettingsFormState(
    val listenHost: String,
    val listenPort: String,
    val authEnabled: Boolean,
    val route: RouteTarget,
) {
    fun toAppConfig(base: AppConfig): ProxySettingsFormResult {
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

        val warnings = buildSet {
            if (candidate.proxy.hasHighSecurityRisk) {
                add(ProxySettingsFormWarning.BroadUnauthenticatedProxy)
            }
        }
        return ProxySettingsFormResult.Valid(
            config = candidate,
            warnings = warnings,
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
    ) : ProxySettingsFormResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
    ) : ProxySettingsFormResult
}

enum class ProxySettingsFormWarning {
    BroadUnauthenticatedProxy,
}

class ProxySettingsFormController(
    private val loadConfig: () -> AppConfig,
    private val saveConfig: (AppConfig) -> Unit,
) {
    fun save(form: ProxySettingsFormState): ProxySettingsSaveResult {
        val result = form.toAppConfig(base = loadConfig())
        return when (result) {
            is ProxySettingsFormResult.Invalid -> ProxySettingsSaveResult.Invalid(result.errors)
            is ProxySettingsFormResult.Valid -> {
                saveConfig(result.config)
                ProxySettingsSaveResult.Saved(
                    config = result.config,
                    warnings = result.warnings,
                )
            }
        }
    }
}

sealed interface ProxySettingsSaveResult {
    data class Saved(
        val config: AppConfig,
        val warnings: Set<ProxySettingsFormWarning>,
    ) : ProxySettingsSaveResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
    ) : ProxySettingsSaveResult
}

private fun String.toStrictPortOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it in TCP_PORT_RANGE }
}

private val TCP_PORT_RANGE = 1..65_535
private const val INVALID_PORT_SENTINEL = 0
