package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxySettingsFormModelTest {
    @Test
    fun `form state starts from persisted proxy and route config`() {
        val config = AppConfig.default().copy(
            network = AppConfig.default().network.copy(
                defaultRoutePolicy = RouteTarget.Cellular,
            ),
            cloudflare = AppConfig.default().cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = true,
                managementHostnameLabel = "manage.example.com",
            ),
            rotation = AppConfig.default().rotation.copy(
                strictIpChangeRequired = true,
                mobileDataOffDelay = 7.seconds,
                networkReturnTimeout = 90.seconds,
                cooldown = 300.seconds,
            ),
        )

        val form = ProxySettingsFormState.from(config)

        assertEquals("0.0.0.0", form.listenHost)
        assertEquals("8080", form.listenPort)
        assertEquals(true, form.authEnabled)
        assertEquals("64", form.maxConcurrentConnections)
        assertEquals(RouteTarget.Cellular, form.route)
        assertEquals(true, form.cloudflareEnabled)
        assertEquals("manage.example.com", form.cloudflareHostnameLabel)
        assertEquals(true, form.strictIpChangeRequired)
        assertEquals("7", form.mobileDataOffDelaySeconds)
        assertEquals("90", form.networkReturnTimeoutSeconds)
        assertEquals("300", form.cooldownSeconds)
        assertEquals(false, form.rootOperationsEnabled)
    }

    @Test
    fun `valid settings produce updated config and broad unauthenticated warning`() {
        val result = ProxySettingsFormState(
            listenHost = " 0.0.0.0 ",
            listenPort = " 8181 ",
            authEnabled = false,
            maxConcurrentConnections = "12",
            route = RouteTarget.Vpn,
        ).toAppConfig(base = AppConfig.default())

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals("0.0.0.0", saved.config.proxy.listenHost)
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
        assertEquals(12, saved.config.proxy.maxConcurrentConnections)
        assertEquals(RouteTarget.Vpn, saved.config.network.defaultRoutePolicy)
        assertEquals(setOf(ProxySettingsFormWarning.BroadUnauthenticatedProxy), saved.warnings)
    }

    @Test
    fun `invalid host and port return validation errors without throwing`() {
        val result = ProxySettingsFormState(
            listenHost = "not-an-ip",
            listenPort = "+8080",
            authEnabled = true,
            route = RouteTarget.Automatic,
        ).toAppConfig(base = AppConfig.default())

        val invalid = result as ProxySettingsFormResult.Invalid
        assertEquals(
            listOf(ConfigValidationError.InvalidListenHost, ConfigValidationError.InvalidListenPort),
            invalid.errors,
        )
    }

    @Test
    fun `blank and out of range ports are rejected`() {
        listOf("", "0", "65536").forEach { port ->
            val result = ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = port,
                authEnabled = true,
                route = RouteTarget.Automatic,
            ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid &&
                    ConfigValidationError.InvalidListenPort in result.errors,
                "Port $port should be invalid",
            )
        }
    }

    @Test
    fun `blank zero negative and fractional maximum concurrent connections are rejected`() {
        val invalidValues = listOf("", "0", "-1", "1.5")

        invalidValues.forEach { invalidValue ->
            val result = ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8181",
                authEnabled = true,
                maxConcurrentConnections = invalidValue,
                route = RouteTarget.Automatic,
            ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid && result.invalidMaxConcurrentConnections,
                "maxConcurrentConnections=$invalidValue should be invalid",
            )
        }
    }

    @Test
    fun `proxy settings save ignores unrelated stale Cloudflare token presence validation`() {
        val staleCloudflareConfig = AppConfig.default().copy(
            cloudflare = AppConfig.default().cloudflare.copy(
                enabled = true,
                tunnelTokenPresent = false,
            ),
        )

        val result = ProxySettingsFormState(
            listenHost = "127.0.0.1",
            listenPort = "8181",
            authEnabled = true,
            route = RouteTarget.Automatic,
        ).toAppConfig(base = staleCloudflareConfig)

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals("127.0.0.1", saved.config.proxy.listenHost)
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(staleCloudflareConfig.cloudflare, saved.config.cloudflare)
    }

    @Test
    fun `valid settings update rotation controls`() {
        val base = AppConfig.default().copy(
            rotation = RotationConfig(
                strictIpChangeRequired = false,
                mobileDataOffDelay = 7.seconds,
                networkReturnTimeout = 90.seconds,
                cooldown = 300.seconds,
            ),
        )

        val result = ProxySettingsFormState(
            listenHost = "127.0.0.1",
            listenPort = "8181",
            authEnabled = true,
            route = RouteTarget.Automatic,
            strictIpChangeRequired = true,
            mobileDataOffDelaySeconds = " 11 ",
            networkReturnTimeoutSeconds = "120",
            cooldownSeconds = "360",
            rootOperationsEnabled = true,
        ).toAppConfig(base = base)

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals(true, saved.config.rotation.strictIpChangeRequired)
        assertEquals(11.seconds, saved.config.rotation.mobileDataOffDelay)
        assertEquals(120.seconds, saved.config.rotation.networkReturnTimeout)
        assertEquals(360.seconds, saved.config.rotation.cooldown)
        assertEquals(true, saved.config.root.operationsEnabled)
    }

    @Test
    fun `blank zero negative and fractional rotation timing fields are rejected`() {
        val invalidValues = listOf("", "0", "-1", "1.5")

        invalidValues.forEach { invalidValue ->
            val result = ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8181",
                authEnabled = true,
                route = RouteTarget.Automatic,
                mobileDataOffDelaySeconds = invalidValue,
            ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid && result.invalidRotationTiming,
                "mobileDataOffDelaySeconds=$invalidValue should be invalid",
            )
        }
    }
}
