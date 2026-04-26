package com.cellularproxy.shared.proxy

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.network.RouteSelector

sealed interface ProxyServiceStartupDecision {
    data class Ready(
        val listenHost: String,
        val listenPort: Int,
        val configuredRoute: RouteTarget,
        val routeCandidates: List<NetworkDescriptor>,
        val hasHighSecurityRisk: Boolean,
    ) : ProxyServiceStartupDecision {
        init {
            require(listenHost.isSupportedNumericIpv4Address()) {
                "Ready startup requires a supported numeric IPv4 listen host"
            }
            require(listenPort in 1..65_535) { "Ready startup requires a TCP listen port" }
            require(routeCandidates.isNotEmpty()) { "Ready startup requires at least one route candidate" }
            require(routeCandidates.all(NetworkDescriptor::isAvailable)) {
                "Ready startup route candidates must be available"
            }
            require(RouteSelector.candidatesFor(configuredRoute, routeCandidates) == routeCandidates) {
                "Ready startup route candidates must match the configured route"
            }
            require(!hasHighSecurityRisk || listenHost == BROAD_LISTEN_HOST) {
                "Ready startup high-risk state requires a broad listen host"
            }
        }
    }

    data class Failed(
        val startupError: ProxyStartupError,
        val status: ProxyServiceStatus,
    ) : ProxyServiceStartupDecision {
        init {
            require(status.startupError == startupError) {
                "Failed startup status must carry the same startup error"
            }
        }
    }
}

object ProxyServiceStartupPolicy {
    fun evaluate(
        config: AppConfig,
        managementApiTokenPresent: Boolean,
        observedNetworks: List<NetworkDescriptor>,
    ): ProxyServiceStartupDecision {
        config.validate().errors.firstOrNull()?.let { error ->
            return failed(
                startupError = error.toStartupError(),
                configuredRoute = config.network.defaultRoutePolicy,
            )
        }

        if (!managementApiTokenPresent) {
            return failed(
                startupError = ProxyStartupError.MissingManagementApiToken,
                configuredRoute = config.network.defaultRoutePolicy,
            )
        }

        val routeCandidates = RouteSelector.candidatesFor(
            target = config.network.defaultRoutePolicy,
            networks = observedNetworks,
        )
        if (routeCandidates.isEmpty()) {
            return failed(
                startupError = ProxyStartupError.UnavailableSelectedRoute,
                configuredRoute = config.network.defaultRoutePolicy,
            )
        }

        return ProxyServiceStartupDecision.Ready(
            listenHost = config.proxy.listenHost,
            listenPort = config.proxy.listenPort,
            configuredRoute = config.network.defaultRoutePolicy,
            routeCandidates = routeCandidates,
            hasHighSecurityRisk = config.proxy.hasHighSecurityRisk,
        )
    }

    private fun failed(
        startupError: ProxyStartupError,
        configuredRoute: RouteTarget,
    ): ProxyServiceStartupDecision.Failed =
        ProxyServiceStartupDecision.Failed(
            startupError = startupError,
            status = ProxyServiceStatus.failed(
                startupError = startupError,
                configuredRoute = configuredRoute,
            ),
        )
}

private fun ConfigValidationError.toStartupError(): ProxyStartupError =
    when (this) {
        ConfigValidationError.InvalidListenHost -> ProxyStartupError.InvalidListenAddress
        ConfigValidationError.InvalidListenPort -> ProxyStartupError.InvalidListenPort
        ConfigValidationError.MissingCloudflareTunnelToken -> ProxyStartupError.MissingCloudflareTunnelToken
    }

private fun String.isSupportedNumericIpv4Address(): Boolean {
    if (isEmpty() || this != trim()) {
        return false
    }

    val parts = split(".")
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
}

private const val BROAD_LISTEN_HOST = "0.0.0.0"
