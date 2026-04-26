package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxySettingsFormControllerTest {
    @Test
    fun `valid form saves updated proxy settings while preserving unrelated config`() {
        val original = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = true,
                managementHostnameLabel = "manage.example.com",
            ),
            rotation = AppConfig.default().rotation.copy(
                mobileDataOffDelay = 5.seconds,
                networkReturnTimeout = 70.seconds,
                cooldown = 200.seconds,
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
                maxConcurrentConnections = "9",
                route = RouteTarget.WiFi,
                strictIpChangeRequired = true,
                mobileDataOffDelaySeconds = "8",
                networkReturnTimeoutSeconds = "95",
                cooldownSeconds = "240",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals("127.0.0.1", saved.config.proxy.listenHost)
        assertEquals(8888, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
        assertEquals(9, saved.config.proxy.maxConcurrentConnections)
        assertEquals(RouteTarget.WiFi, saved.config.network.defaultRoutePolicy)
        assertEquals(true, saved.config.rotation.strictIpChangeRequired)
        assertEquals(8.seconds, saved.config.rotation.mobileDataOffDelay)
        assertEquals(95.seconds, saved.config.rotation.networkReturnTimeout)
        assertEquals(240.seconds, saved.config.rotation.cooldown)
        assertEquals(original.cloudflare, saved.config.cloudflare)
        assertEquals(listOf(saved.config), savedConfigs)
    }

    @Test
    fun `invalid rotation timing is rejected before sensitive config is loaded`() {
        val savedConfigs = mutableListOf<AppConfig>()
        var loadSensitiveConfigCalled = false
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = savedConfigs::add,
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
                networkReturnTimeoutSeconds = "0",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidRotationTiming)
        assertTrue(savedConfigs.isEmpty())
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `invalid maximum concurrent connections is rejected before sensitive config is loaded`() {
        val savedConfigs = mutableListOf<AppConfig>()
        var loadSensitiveConfigCalled = false
        val controller = ProxySettingsFormController(
            loadConfig = AppConfig::default,
            saveConfig = savedConfigs::add,
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
                maxConcurrentConnections = "0",
                route = RouteTarget.Automatic,
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidMaxConcurrentConnections)
        assertTrue(savedConfigs.isEmpty())
        assertEquals(false, loadSensitiveConfigCalled)
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
    fun `management token field updates encrypted management token while preserving other sensitive values`() {
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "old-management-token",
            cloudflareTunnelToken = "cloudflare-token",
        )
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
                managementApiToken = "new-management-token",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals("new-management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals("cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `cloudflare fields enable tunnel with encrypted token and display hostname label`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
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
                route = RouteTarget.Automatic,
                cloudflareEnabled = true,
                cloudflareTunnelToken = "new-cloudflare-token",
                cloudflareHostnameLabel = " manage.example.com ",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(true, saved.config.cloudflare.enabled)
        assertEquals(true, saved.config.cloudflare.tunnelTokenPresent)
        assertEquals("manage.example.com", saved.config.cloudflare.managementHostnameLabel)
        assertEquals("new-cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals(listOf(saved.config), savedConfigs)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `blank cloudflare token field preserves existing token while allowing hostname removal`() {
        val originalConfig = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = true,
                managementHostnameLabel = "old.example.com",
            ),
        )
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
            cloudflareTunnelToken = "existing-cloudflare-token",
        )
        val controller = ProxySettingsFormController(
            loadConfig = { originalConfig },
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
                cloudflareEnabled = true,
                cloudflareHostnameLabel = "   ",
            ),
        )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(true, saved.config.cloudflare.enabled)
        assertEquals(true, saved.config.cloudflare.tunnelTokenPresent)
        assertEquals(null, saved.config.cloudflare.managementHostnameLabel)
        assertEquals("existing-cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
    }

    @Test
    fun `enabling cloudflare without existing or edited tunnel token is rejected without saving`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "management-token",
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
                route = RouteTarget.Automatic,
                cloudflareEnabled = true,
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidCloudflareTunnelToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `blank after trim cloudflare tunnel token edit is rejected before sensitive config is loaded`() {
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
                cloudflareTunnelToken = "   ",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidCloudflareTunnelToken)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `management token edit with surrounding whitespace is rejected without saving`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
            managementApiToken = "old-management-token",
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
                route = RouteTarget.Automatic,
                managementApiToken = " new-management-token ",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `blank management token field preserves current sensitive management token`() {
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
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals(listOf(originalSensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `blank after trim management token edit is rejected without saving plain or sensitive state`() {
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
                managementApiToken = "   ",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
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

    @Test
    fun `blank after trim management token edit is rejected before sensitive config is loaded`() {
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
                managementApiToken = "   ",
            ),
        )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertEquals(false, loadSensitiveConfigCalled)
    }
}
