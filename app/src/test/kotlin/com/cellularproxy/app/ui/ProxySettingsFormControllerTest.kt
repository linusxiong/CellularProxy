package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
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
}
