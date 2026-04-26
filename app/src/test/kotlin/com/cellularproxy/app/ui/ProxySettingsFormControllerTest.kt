package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxySettingsFormControllerTest {
    @Test
    fun `valid form saves updated proxy settings while preserving unrelated config`() {
        val original = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = true,
                managementHostnameLabel = "manage.example.com",
            ),
        )
        val savedConfigs = mutableListOf<AppConfig>()
        val controller = ProxySettingsFormController(
            loadConfig = { original },
            saveConfig = savedConfigs::add,
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = false,
                route = RouteTarget.WiFi,
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals("127.0.0.1", saved.config.proxy.listenHost)
        assertEquals(8888, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
        assertEquals(RouteTarget.WiFi, saved.config.network.defaultRoutePolicy)
        assertEquals(original.rotation, saved.config.rotation)
        assertEquals(original.cloudflare, saved.config.cloudflare)
        assertEquals(listOf(saved.config), savedConfigs)
    }

    @Test
    fun `invalid form returns errors and does not save`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = savedConfigs::add,
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "bad host",
                listenPort = "8080",
                authEnabled = true,
                route = RouteTarget.Automatic,
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertEquals(listOf(ConfigValidationError.InvalidListenHost), invalid.errors)
        assertTrue(savedConfigs.isEmpty())
    }

    @Test
    fun `credential fields update encrypted proxy credential while preserving other sensitive values`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
            cloudflareTunnelToken = "cloudflare-token",
        )
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = savedConfigs::add,
            loadSensitiveConfig = { originalSensitiveConfig },
            saveSensitiveConfig = savedSensitiveConfigs::add,
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Cellular,
                proxyUsername = "new-user",
                proxyPassword = "new-pass",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(ProxyCredential(username = "new-user", password = "new-pass"), saved.sensitiveConfig?.proxyCredential)
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals("cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(listOf(saved.config), savedConfigs)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `credential edit preserves exact password text including surrounding spaces`() {
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
        )
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = {},
            loadSensitiveConfig = { originalSensitiveConfig },
            saveSensitiveConfig = {},
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Automatic,
                proxyUsername = "new-user",
                proxyPassword = " new-pass ",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(" new-pass ", saved.sensitiveConfig?.proxyCredential?.password)
    }

    @Test
    fun `blank credential fields preserve current sensitive proxy credential`() {
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
        )
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = {},
            loadSensitiveConfig = { originalSensitiveConfig },
            saveSensitiveConfig = savedSensitiveConfigs::add,
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Automatic,
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals(listOf(originalSensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `partial credential edit is rejected without saving plain or sensitive state`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = savedConfigs::add,
            loadSensitiveConfig = {
                SensitiveConfig(
                    proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                    managementApiToken = "management-token",
                )
            },
            saveSensitiveConfig = savedSensitiveConfigs::add,
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Automatic,
                proxyUsername = "new-user",
                proxyPassword = "",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidProxyCredential)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `invalid plain form is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = {},
            loadSensitiveConfig = {
                loadSensitiveConfigCalled = true
                SensitiveConfig(
                    proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                    managementApiToken = "management-token",
                )
            },
            saveSensitiveConfig = {},
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "bad host",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Automatic,
            ),
        )

        assertTrue(result is ProxySettingsSaveResult.Invalid)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `partial credential edit is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = {},
            loadSensitiveConfig = {
                loadSensitiveConfigCalled = true
                SensitiveConfig(
                    proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                    managementApiToken = "management-token",
                )
            },
            saveSensitiveConfig = {},
        )

        val result = controller.save(
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8888",
                authEnabled = true,
                route = RouteTarget.Automatic,
                proxyUsername = "new-user",
                proxyPassword = "",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidProxyCredential)
        assertEquals(false, loadSensitiveConfigCalled)
    }
}
