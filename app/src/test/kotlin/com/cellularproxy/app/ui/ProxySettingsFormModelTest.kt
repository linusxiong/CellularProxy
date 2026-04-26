package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxySettingsFormModelTest {
    @Test
    fun `form state starts from persisted proxy and route config`() {
        val config = AppConfig.default().copy(
            network = AppConfig.default().network.copy(
                defaultRoutePolicy = RouteTarget.Cellular,
            ),
        )

        val form = ProxySettingsFormState.from(config)

        assertEquals("0.0.0.0", form.listenHost)
        assertEquals("8080", form.listenPort)
        assertEquals(true, form.authEnabled)
        assertEquals(RouteTarget.Cellular, form.route)
    }

    @Test
    fun `valid settings produce updated config and broad unauthenticated warning`() {
        val result = ProxySettingsFormState(
            listenHost = " 0.0.0.0 ",
            listenPort = " 8181 ",
            authEnabled = false,
            route = RouteTarget.Vpn,
        ).toAppConfig(base = AppConfig.default())

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals("0.0.0.0", saved.config.proxy.listenHost)
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
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
}
