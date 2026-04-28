package com.cellularproxy.app.e2e

import android.os.Bundle
import com.cellularproxy.cloudflare.CloudflareTunnelToken
import com.cellularproxy.cloudflare.CloudflareTunnelTokenParseResult
import java.net.URI
import java.util.Properties

sealed interface CloudflareE2eValidationConfig {
    val safeSummary: String

    data object Disabled : CloudflareE2eValidationConfig {
        override val safeSummary: String = "Cloudflare e2e validation disabled"
    }

    data class Ready(
        val tunnelToken: String,
        val managementApiToken: String?,
        val managementHostname: String?,
    ) : CloudflareE2eValidationConfig {
        override val safeSummary: String =
            "Cloudflare e2e validation configured: " +
                "tunnelToken=present, " +
                "managementApiToken=${managementApiToken.presenceLabel()}, " +
                "hostname=${managementHostname.safeHostnameSummary()}"

        override fun toString(): String = "Ready(tunnelToken=[REDACTED], " +
            "managementApiToken=${managementApiToken.presenceLabel()}, " +
            "managementHostname=${managementHostname.safeHostnameSummary()})"
    }

    data object InvalidTunnelToken : CloudflareE2eValidationConfig {
        override val safeSummary: String = "Cloudflare e2e validation unavailable: tunnelToken=invalid"

        override fun toString(): String = "InvalidTunnelToken(tunnelToken=[REDACTED])"
    }

    companion object {
        fun fromProperties(properties: Properties): CloudflareE2eValidationConfig = fromLocalValues(
            CloudflareE2eValidationConfigKeys.allWithAliases.associateWith { key ->
                properties.getProperty(key)
            },
        )

        fun fromInstrumentationArguments(
            arguments: Map<String, String?>,
        ): CloudflareE2eValidationConfig = fromLocalValues(
            mapOf(
                CloudflareE2eValidationConfigKeys.localTunnelToken to
                    arguments[CloudflareE2eValidationInstrumentationArguments.tunnelToken],
                CloudflareE2eValidationConfigKeys.localManagementApiToken to
                    arguments[CloudflareE2eValidationInstrumentationArguments.managementApiToken],
                CloudflareE2eValidationConfigKeys.localManagementHostname to
                    arguments[CloudflareE2eValidationInstrumentationArguments.managementHostname],
            ),
        )

        fun fromInstrumentationArguments(arguments: Bundle): CloudflareE2eValidationConfig = fromInstrumentationArguments(
            CloudflareE2eValidationInstrumentationArguments.all.associateWith(arguments::getString),
        )

        fun fromLocalValues(values: Map<String, String?>): CloudflareE2eValidationConfig {
            val tunnelToken =
                values.localValueFor(CloudflareE2eValidationConfigKeys.tunnelToken).trimmedOrNull()
                    ?: return Disabled
            if (CloudflareTunnelToken.parse(tunnelToken) !is CloudflareTunnelTokenParseResult.Valid) {
                return InvalidTunnelToken
            }
            return Ready(
                tunnelToken = tunnelToken,
                managementApiToken =
                    values
                        .localValueFor(CloudflareE2eValidationConfigKeys.managementApiToken)
                        .trimmedOrNull(),
                managementHostname =
                    values
                        .localValueFor(CloudflareE2eValidationConfigKeys.managementHostname)
                        .trimmedOrNull()
                        ?.safeHostnameSummary(),
            )
        }
    }
}

object CloudflareE2eValidationInstrumentationArguments {
    const val tunnelToken: String = "cloudflareTunnelToken"
    const val managementApiToken: String = "cloudflareManagementApiToken"
    const val managementHostname: String = "cloudflareManagementHostname"
    val all: Set<String> = setOf(tunnelToken, managementApiToken, managementHostname)
}

object CloudflareE2eValidationConfigKeys {
    const val tunnelToken: String = "cellularproxy.cloudflareTunnelToken"
    const val managementApiToken: String = "cellularproxy.managementApiToken"
    const val managementHostname: String = "cellularproxy.cloudflareManagementHostname"
    const val localTunnelToken: String = "cellularproxy.e2e.cloudflareTunnelToken"
    const val localManagementApiToken: String = "cellularproxy.e2e.cloudflareManagementApiToken"
    const val localManagementHostname: String = "cellularproxy.e2e.cloudflareManagementHostname"
    val all: Set<String> = setOf(tunnelToken, managementApiToken, managementHostname)
    val allWithAliases: Set<String> =
        setOf(
            tunnelToken,
            managementApiToken,
            managementHostname,
            localTunnelToken,
            localManagementApiToken,
            localManagementHostname,
        )

    fun localAliasFor(key: String): String? = when (key) {
        tunnelToken -> localTunnelToken
        managementApiToken -> localManagementApiToken
        managementHostname -> localManagementHostname
        else -> null
    }
}

private fun Map<String, String?>.localValueFor(key: String): String? {
    val aliasValue = CloudflareE2eValidationConfigKeys.localAliasFor(key)?.let(this::get)
    return listOfNotNull(this[key], aliasValue).firstOrNull { it.trimmedOrNull() != null }
}

private fun String?.trimmedOrNull(): String? = this
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun String?.presenceLabel(): String = if (this == null) {
    "missing"
} else {
    "present"
}

private fun String?.safeHostnameSummary(): String {
    val value = this?.summaryLine() ?: return "not configured"
    if ("://" !in value) {
        return value.safeSchemeLessHostnameSummary()
    }
    return runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme == null || host == null) {
            value.safeUrlLikeHostnameFallback()
        } else {
            URI(scheme, null, host, uri.port, null, null, null).toString()
        }
    }.getOrElse { value.safeUrlLikeHostnameFallback() }
}

private fun String.summaryLine(): String = lineSequence().firstOrNull().orEmpty()

private fun String.safeSchemeLessHostnameSummary(): String = substringBefore('/')
    .substringBefore('\\')
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('@')

private fun String.safeUrlLikeHostnameFallback(): String {
    val scheme = substringBefore("://").lowercase()
    val authority =
        substringAfter("://")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
    return if (scheme.isBlank()) {
        authority
    } else if (authority.isBlank()) {
        "$scheme://"
    } else {
        "$scheme://$authority"
    }
}
