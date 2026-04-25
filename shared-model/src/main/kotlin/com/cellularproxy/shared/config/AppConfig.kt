package com.cellularproxy.shared.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val proxy: ProxyConfig,
    val network: NetworkConfig,
    val rotation: RotationConfig,
    val cloudflare: CloudflareConfig,
) {
    fun validate(): ConfigValidationResult {
        val errors = buildList {
            if (!proxy.listenHost.isSupportedListenHost()) {
                add(ConfigValidationError.InvalidListenHost)
            }
            if (proxy.listenPort !in TCP_PORT_RANGE) {
                add(ConfigValidationError.InvalidListenPort)
            }
            if (cloudflare.enabled && !cloudflare.tunnelTokenPresent) {
                add(ConfigValidationError.MissingCloudflareTunnelToken)
            }
        }

        return ConfigValidationResult(errors)
    }

    companion object {
        fun default(): AppConfig = AppConfig(
            proxy = ProxyConfig(),
            network = NetworkConfig(),
            rotation = RotationConfig(),
            cloudflare = CloudflareConfig(),
        )
    }
}

data class ProxyConfig(
    val listenHost: String = "0.0.0.0",
    val listenPort: Int = 8080,
    val authEnabled: Boolean = true,
)

data class NetworkConfig(
    val defaultRoutePolicy: RouteTarget = RouteTarget.Automatic,
)

data class RotationConfig(
    val strictIpChangeRequired: Boolean = false,
    val mobileDataOffDelay: Duration = 3.seconds,
    val networkReturnTimeout: Duration = 60.seconds,
    val cooldown: Duration = 180.seconds,
)

data class CloudflareConfig(
    val enabled: Boolean = false,
    val tunnelTokenPresent: Boolean = false,
    val managementHostnameLabel: String? = null,
)

enum class RouteTarget {
    WiFi,
    Cellular,
    Vpn,
    Automatic,
}

data class ConfigValidationResult(
    val errors: List<ConfigValidationError>,
) {
    val isValid: Boolean = errors.isEmpty()
}

enum class ConfigValidationError {
    InvalidListenHost,
    InvalidListenPort,
    MissingCloudflareTunnelToken,
}

private val TCP_PORT_RANGE = 1..65_535

private fun String.isSupportedListenHost(): Boolean {
    if (isEmpty() || this != trim()) {
        return false
    }

    val parts = split(".")
    if (parts.size != 4) {
        return false
    }

    return parts.all { part ->
        part.isNotEmpty() &&
            part.all(Char::isDigit) &&
            part.toIntOrNull() in 0..255
    }
}
