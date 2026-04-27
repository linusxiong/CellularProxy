package com.cellularproxy.cloudflare

object CloudflareTunnelStartupPolicy {
    fun evaluate(
        enabled: Boolean,
        rawTunnelToken: String?,
    ): CloudflareTunnelStartupDecision {
        if (!enabled) {
            return CloudflareTunnelStartupDecision.Disabled
        }

        val token = rawTunnelToken?.trim()
        if (token.isNullOrEmpty()) {
            return CloudflareTunnelStartupDecision.Failed(
                failure = CloudflareTunnelStartupFailure.MissingTunnelToken,
            )
        }

        return when (val parseResult = CloudflareTunnelToken.parse(token)) {
            is CloudflareTunnelTokenParseResult.Valid ->
                CloudflareTunnelStartupDecision.Ready(parseResult.token.credentials)
            is CloudflareTunnelTokenParseResult.Invalid ->
                CloudflareTunnelStartupDecision.Failed(
                    failure = CloudflareTunnelStartupFailure.InvalidTunnelToken,
                )
        }
    }
}

sealed interface CloudflareTunnelStartupDecision {
    data object Disabled : CloudflareTunnelStartupDecision

    class Ready(
        val credentials: CloudflareTunnelCredentials,
    ) : CloudflareTunnelStartupDecision {
        override fun toString(): String = "CloudflareTunnelStartupDecision.Ready(credentials=<redacted>)"
    }

    data class Failed(
        val failure: CloudflareTunnelStartupFailure,
    ) : CloudflareTunnelStartupDecision
}

enum class CloudflareTunnelStartupFailure {
    MissingTunnelToken,
    InvalidTunnelToken,
}
