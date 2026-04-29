package com.cellularproxy.app.config

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RootConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object PlainConfigPreferenceKeys {
    val proxyListenHost = stringPreferencesKey("proxy.listenHost")
    val proxyListenPort = intPreferencesKey("proxy.listenPort")
    val proxyAuthEnabled = booleanPreferencesKey("proxy.authEnabled")
    val proxyMaxConcurrentConnections = intPreferencesKey("proxy.maxConcurrentConnections")
    val defaultRoutePolicy = stringPreferencesKey("network.defaultRoutePolicy")
    val strictIpChangeRequired = booleanPreferencesKey("rotation.strictIpChangeRequired")
    val mobileDataOffDelay = longPreferencesKey("rotation.mobileDataOffDelay")
    val networkReturnTimeout = longPreferencesKey("rotation.networkReturnTimeout")
    val cooldown = longPreferencesKey("rotation.cooldown")
    val rootOperationsEnabled = booleanPreferencesKey("root.operationsEnabled")
    val cloudflareEnabled = booleanPreferencesKey("cloudflare.enabled")
    val cloudflareTunnelTokenPresent = booleanPreferencesKey("cloudflare.tunnelTokenPresent")
    val cloudflareManagementHostnameLabel = stringPreferencesKey("cloudflare.managementHostnameLabel")
}

object PlainConfigPreferencesMapper {
    fun toPreferences(config: AppConfig): Preferences = mutablePreferencesOf().also { preferences ->
        replacePreferences(preferences, config)
    }

    fun replacePreferences(
        preferences: MutablePreferences,
        config: AppConfig,
    ) {
        preferences.clear()
        preferences.putAll(
            PlainConfigPreferenceKeys.proxyListenHost to config.proxy.listenHost,
            PlainConfigPreferenceKeys.proxyListenPort to config.proxy.listenPort,
            PlainConfigPreferenceKeys.proxyAuthEnabled to config.proxy.authEnabled,
            PlainConfigPreferenceKeys.proxyMaxConcurrentConnections to config.proxy.maxConcurrentConnections,
            PlainConfigPreferenceKeys.defaultRoutePolicy to config.network.defaultRoutePolicy.name,
            PlainConfigPreferenceKeys.strictIpChangeRequired to config.rotation.strictIpChangeRequired,
            PlainConfigPreferenceKeys.mobileDataOffDelay to config.rotation.mobileDataOffDelay.inWholeMilliseconds,
            PlainConfigPreferenceKeys.networkReturnTimeout to config.rotation.networkReturnTimeout.inWholeMilliseconds,
            PlainConfigPreferenceKeys.cooldown to config.rotation.cooldown.inWholeMilliseconds,
            PlainConfigPreferenceKeys.rootOperationsEnabled to config.root.operationsEnabled,
            PlainConfigPreferenceKeys.cloudflareEnabled to config.cloudflare.enabled,
            PlainConfigPreferenceKeys.cloudflareTunnelTokenPresent to config.cloudflare.tunnelTokenPresent,
        )
        config.cloudflare.managementHostnameLabel?.let { hostnameLabel ->
            preferences[PlainConfigPreferenceKeys.cloudflareManagementHostnameLabel] = hostnameLabel
        }
    }

    fun fromPreferences(preferences: Preferences): AppConfig {
        val defaults = AppConfig.default()

        return AppConfig(
            proxy =
                ProxyConfig(
                    listenHost =
                        preferences[PlainConfigPreferenceKeys.proxyListenHost]
                            ?.takeIf { it.isSupportedListenHost() }
                            ?: defaults.proxy.listenHost,
                    listenPort =
                        preferences[PlainConfigPreferenceKeys.proxyListenPort]
                            ?.takeIf { it in TCP_PORT_RANGE }
                            ?: defaults.proxy.listenPort,
                    authEnabled =
                        preferences[PlainConfigPreferenceKeys.proxyAuthEnabled]
                            ?: DEFAULT_PROXY_AUTH_ENABLED,
                    maxConcurrentConnections =
                        preferences[PlainConfigPreferenceKeys.proxyMaxConcurrentConnections]
                            ?.takeIf { it > 0 }
                            ?: defaults.proxy.maxConcurrentConnections,
                ),
            network =
                NetworkConfig(
                    defaultRoutePolicy =
                        preferences[PlainConfigPreferenceKeys.defaultRoutePolicy]
                            ?.toRouteTargetOrNull()
                            ?: defaults.network.defaultRoutePolicy,
                ),
            rotation =
                RotationConfig(
                    strictIpChangeRequired =
                        preferences[PlainConfigPreferenceKeys.strictIpChangeRequired]
                            ?: defaults.rotation.strictIpChangeRequired,
                    mobileDataOffDelay =
                        preferences[PlainConfigPreferenceKeys.mobileDataOffDelay]
                            .toPositiveDurationOrDefault(defaults.rotation.mobileDataOffDelay),
                    networkReturnTimeout =
                        preferences[PlainConfigPreferenceKeys.networkReturnTimeout]
                            .toPositiveDurationOrDefault(defaults.rotation.networkReturnTimeout),
                    cooldown =
                        preferences[PlainConfigPreferenceKeys.cooldown]
                            .toPositiveDurationOrDefault(defaults.rotation.cooldown),
                ),
            root =
                RootConfig(
                    operationsEnabled =
                        preferences[PlainConfigPreferenceKeys.rootOperationsEnabled]
                            ?: defaults.root.operationsEnabled,
                ),
            cloudflare =
                CloudflareConfig(
                    enabled =
                        preferences[PlainConfigPreferenceKeys.cloudflareEnabled]
                            ?: defaults.cloudflare.enabled,
                    tunnelTokenPresent =
                        preferences[PlainConfigPreferenceKeys.cloudflareTunnelTokenPresent]
                            ?: defaults.cloudflare.tunnelTokenPresent,
                    managementHostnameLabel =
                        preferences[PlainConfigPreferenceKeys.cloudflareManagementHostnameLabel]
                            ?: defaults.cloudflare.managementHostnameLabel,
                ),
        )
    }
}

private fun String.toRouteTargetOrNull(): RouteTarget? = RouteTarget.entries.firstOrNull { it.name == this }

private fun Long?.toPositiveDurationOrDefault(default: Duration): Duration = this
    ?.takeIf { it > 0L }
    ?.milliseconds
    ?: default

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

private val TCP_PORT_RANGE = 1..65_535
private const val DEFAULT_PROXY_AUTH_ENABLED = false
