package com.cellularproxy.app.config

import androidx.datastore.preferences.core.preferencesOf
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RootConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlainConfigPreferencesMapperTest {
    @Test
    fun `round trips non-sensitive app configuration through preferences`() {
        val config = AppConfig(
            proxy = ProxyConfig(
                listenHost = "127.0.0.1",
                listenPort = 8_888,
                authEnabled = false,
                maxConcurrentConnections = 12,
            ),
            network = NetworkConfig(defaultRoutePolicy = RouteTarget.Cellular),
            rotation = RotationConfig(
                strictIpChangeRequired = true,
                mobileDataOffDelay = 5.seconds,
                networkReturnTimeout = 75.seconds,
                cooldown = 240.seconds,
            ),
            root = RootConfig(
                operationsEnabled = true,
            ),
            cloudflare = CloudflareConfig(
                enabled = true,
                tunnelTokenPresent = true,
                managementHostnameLabel = "manage.example.com",
            ),
        )

        val preferences = PlainConfigPreferencesMapper.toPreferences(config)
        val restored = PlainConfigPreferencesMapper.fromPreferences(preferences)

        assertEquals(config, restored)
    }

    @Test
    fun `missing preferences read as design spec defaults`() {
        val restored = PlainConfigPreferencesMapper.fromPreferences(preferencesOf())

        assertEquals(AppConfig.default(), restored)
    }

    @Test
    fun `invalid persisted enum and duration values fall back to defaults`() {
        val restored = PlainConfigPreferencesMapper.fromPreferences(
            preferencesOf(
                PlainConfigPreferenceKeys.defaultRoutePolicy to "satellite",
                PlainConfigPreferenceKeys.mobileDataOffDelay to (-1L),
                PlainConfigPreferenceKeys.networkReturnTimeout to 0L,
                PlainConfigPreferenceKeys.cooldown to (-5L),
            ),
        )

        assertEquals(RouteTarget.Automatic, restored.network.defaultRoutePolicy)
        assertEquals(3.seconds, restored.rotation.mobileDataOffDelay)
        assertEquals(60.seconds, restored.rotation.networkReturnTimeout)
        assertEquals(180.seconds, restored.rotation.cooldown)
    }

    @Test
    fun `invalid persisted proxy listener values fall back to defaults`() {
        val restored = PlainConfigPreferencesMapper.fromPreferences(
            preferencesOf(
                PlainConfigPreferenceKeys.proxyListenHost to "example.com",
                PlainConfigPreferenceKeys.proxyListenPort to 65_536,
                PlainConfigPreferenceKeys.proxyMaxConcurrentConnections to 0,
            ),
        )

        assertEquals("0.0.0.0", restored.proxy.listenHost)
        assertEquals(8080, restored.proxy.listenPort)
        assertEquals(64, restored.proxy.maxConcurrentConnections)
    }

    @Test
    fun `plain preferences do not contain raw sensitive credential values`() {
        val preferences = PlainConfigPreferencesMapper.toPreferences(
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(authEnabled = true),
                cloudflare = AppConfig.default().cloudflare.copy(
                    enabled = true,
                    tunnelTokenPresent = true,
                    managementHostnameLabel = "public-hostname-only",
                ),
            ),
        )

        assertEquals(
            setOf(
                PlainConfigPreferenceKeys.proxyListenHost,
                PlainConfigPreferenceKeys.proxyListenPort,
                PlainConfigPreferenceKeys.proxyAuthEnabled,
                PlainConfigPreferenceKeys.proxyMaxConcurrentConnections,
                PlainConfigPreferenceKeys.defaultRoutePolicy,
                PlainConfigPreferenceKeys.strictIpChangeRequired,
                PlainConfigPreferenceKeys.mobileDataOffDelay,
                PlainConfigPreferenceKeys.networkReturnTimeout,
                PlainConfigPreferenceKeys.cooldown,
                PlainConfigPreferenceKeys.rootOperationsEnabled,
                PlainConfigPreferenceKeys.cloudflareEnabled,
                PlainConfigPreferenceKeys.cloudflareTunnelTokenPresent,
                PlainConfigPreferenceKeys.cloudflareManagementHostnameLabel,
            ),
            preferences.asMap().keys,
        )
        assertTrue(preferences[PlainConfigPreferenceKeys.cloudflareTunnelTokenPresent] == true)
    }

    @Test
    fun `replacing preferences removes stale nullable hostname labels`() {
        val preferences = PlainConfigPreferencesMapper.toPreferences(
            AppConfig.default().copy(
                cloudflare = AppConfig.default().cloudflare.copy(
                    managementHostnameLabel = "old.example.com",
                ),
            ),
        ) as androidx.datastore.preferences.core.MutablePreferences

        PlainConfigPreferencesMapper.replacePreferences(
            preferences = preferences,
            config = AppConfig.default(),
        )

        assertFalse(PlainConfigPreferenceKeys.cloudflareManagementHostnameLabel in preferences.asMap().keys)
        assertEquals(null, PlainConfigPreferencesMapper.fromPreferences(preferences).cloudflare.managementHostnameLabel)
    }
}
