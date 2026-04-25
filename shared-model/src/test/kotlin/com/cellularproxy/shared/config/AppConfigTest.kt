package com.cellularproxy.shared.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppConfigTest {
    @Test
    fun `defaults match the design spec`() {
        val config = AppConfig.default()

        assertEquals("0.0.0.0", config.proxy.listenHost)
        assertEquals(8080, config.proxy.listenPort)
        assertTrue(config.proxy.authEnabled)
        assertEquals(RouteTarget.Automatic, config.network.defaultRoutePolicy)
        assertFalse(config.rotation.strictIpChangeRequired)
        assertEquals(3.seconds, config.rotation.mobileDataOffDelay)
        assertEquals(60.seconds, config.rotation.networkReturnTimeout)
        assertEquals(180.seconds, config.rotation.cooldown)
        assertFalse(config.cloudflare.enabled)
        assertEquals(null, config.cloudflare.managementHostnameLabel)
    }

    @Test
    fun `default configuration is valid`() {
        assertTrue(AppConfig.default().validate().isValid)
    }

    @Test
    fun `proxy security risk is high only when authentication is disabled on a broad listener`() {
        val defaultConfig = AppConfig.default()
        val authDisabledBroadListener = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(authEnabled = false, listenHost = "0.0.0.0")
        )
        val authDisabledLocalhost = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(authEnabled = false, listenHost = "127.0.0.1")
        )
        val authEnabledBroadListener = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(authEnabled = true, listenHost = "0.0.0.0")
        )

        assertFalse(defaultConfig.proxy.hasHighSecurityRisk)
        assertTrue(authDisabledBroadListener.proxy.hasHighSecurityRisk)
        assertFalse(authDisabledLocalhost.proxy.hasHighSecurityRisk)
        assertFalse(authEnabledBroadListener.proxy.hasHighSecurityRisk)
    }

    @Test
    fun `listen port must be in the TCP port range`() {
        val minPort = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(listenPort = 1)
        )
        val maxPort = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(listenPort = 65_535)
        )
        val lowPort = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(listenPort = 0)
        )
        val highPort = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(listenPort = 65_536)
        )

        assertTrue(minPort.validate().isValid)
        assertTrue(maxPort.validate().isValid)
        assertContains(lowPort.validate().errors, ConfigValidationError.InvalidListenPort)
        assertContains(highPort.validate().errors, ConfigValidationError.InvalidListenPort)
    }

    @Test
    fun `listen host must be a supported numeric address`() {
        val supportedHosts = listOf("0.0.0.0", "127.0.0.1", "192.168.1.20", "100.64.0.10")

        supportedHosts.forEach { host ->
            val config = AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = host)
            )

            assertTrue(config.validate().isValid, "Expected $host to be valid")
        }
    }

    @Test
    fun `listen host rejects blank malformed hostnames and invalid ipv4 values`() {
        val invalidHosts = listOf(
            " ",
            " proxy.example.com ",
            "proxy.example.com",
            " 127.0.0.1 ",
            "999.1.1.1",
            "-1.1.1.1",
            "127.0.0",
            "127.0.0.1.1",
            "127..0.1",
        )

        invalidHosts.forEach { host ->
            val config = AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = host)
            )

            assertContains(config.validate().errors, ConfigValidationError.InvalidListenHost)
        }
    }

    @Test
    fun `cloudflare cannot be enabled without a tunnel token`() {
        val missingToken = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(enabled = true, tunnelTokenPresent = false)
        )
        val presentToken = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(enabled = true, tunnelTokenPresent = true)
        )

        assertContains(missingToken.validate().errors, ConfigValidationError.MissingCloudflareTunnelToken)
        assertFalse(presentToken.validate().errors.contains(ConfigValidationError.MissingCloudflareTunnelToken))
    }
}
