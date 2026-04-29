package com.cellularproxy.shared.proxy

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyServiceStartupPolicyTest {
    @Test
    fun `ready startup exposes listener route candidates and security risk`() {
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)
        val unavailableCell = NetworkDescriptor("cell", NetworkCategory.Cellular, "Carrier", isAvailable = false)
        val config =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(authEnabled = false),
                network = AppConfig.default().network.copy(defaultRoutePolicy = RouteTarget.Automatic),
            )

        val ready =
            assertIs<ProxyServiceStartupDecision.Ready>(
                ProxyServiceStartupPolicy.evaluate(
                    config = config,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(unavailableCell, wifi),
                ),
            )

        assertEquals("0.0.0.0", ready.listenHost)
        assertEquals(8080, ready.listenPort)
        assertEquals(RouteTarget.Automatic, ready.configuredRoute)
        assertEquals(listOf(wifi), ready.routeCandidates)
        assertTrue(ready.hasHighSecurityRisk)
    }

    @Test
    fun `invalid config maps to structured startup errors before route checks`() {
        val invalidHost =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = "localhost"),
            )
        val invalidPort =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenPort = 0),
            )
        val invalidMaxConcurrentConnections =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(maxConcurrentConnections = 0),
            )
        val missingCloudflareToken =
            AppConfig.default().copy(
                cloudflare = AppConfig.default().cloudflare.copy(enabled = true, tunnelTokenPresent = false),
            )

        assertStartupFailure(
            config = invalidHost,
            expected = ProxyStartupError.InvalidListenAddress,
        )
        assertStartupFailure(
            config = invalidPort,
            expected = ProxyStartupError.InvalidListenPort,
        )
        assertStartupFailure(
            config = invalidMaxConcurrentConnections,
            expected = ProxyStartupError.InvalidMaxConcurrentConnections,
        )
        assertStartupFailure(
            config = missingCloudflareToken,
            expected = ProxyStartupError.MissingCloudflareTunnelToken,
        )
    }

    @Test
    fun `missing management token fails before route checks`() {
        val config =
            AppConfig.default().copy(
                network = AppConfig.default().network.copy(defaultRoutePolicy = RouteTarget.Cellular),
            )
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)

        val failed =
            assertIs<ProxyServiceStartupDecision.Failed>(
                ProxyServiceStartupPolicy.evaluate(
                    config = config,
                    managementApiTokenPresent = false,
                    observedNetworks = listOf(wifi),
                ),
            )

        assertEquals(ProxyStartupError.MissingManagementApiToken, failed.startupError)
        assertEquals(ProxyStartupError.MissingManagementApiToken, failed.status.startupError)
        assertEquals(RouteTarget.Cellular, failed.status.configuredRoute)
    }

    @Test
    fun `explicit route startup fails when selected route is unavailable`() {
        val config =
            AppConfig.default().copy(
                network = AppConfig.default().network.copy(defaultRoutePolicy = RouteTarget.Cellular),
            )
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)

        val failed =
            assertIs<ProxyServiceStartupDecision.Failed>(
                ProxyServiceStartupPolicy.evaluate(
                    config = config,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifi),
                ),
            )

        assertEquals(ProxyStartupError.UnavailableSelectedRoute, failed.startupError)
        assertEquals(RouteTarget.Cellular, failed.status.configuredRoute)
    }

    @Test
    fun `automatic route startup fails when no routeable networks are available`() {
        val unavailableWifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = false)

        val failed =
            assertIs<ProxyServiceStartupDecision.Failed>(
                ProxyServiceStartupPolicy.evaluate(
                    config = AppConfig.default(),
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(unavailableWifi),
                ),
            )

        assertEquals(ProxyStartupError.UnavailableSelectedRoute, failed.startupError)
        assertFalse(failed.status.hasHighSecurityRisk)
    }

    @Test
    fun `ready startup decision rejects values the startup policy cannot produce`() {
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)
        val unavailableWifi = wifi.copy(isAvailable = false)

        assertFailsWith<IllegalArgumentException> {
            ready(listenHost = "localhost", routeCandidates = listOf(wifi))
        }
        assertFailsWith<IllegalArgumentException> {
            ready(configuredRoute = RouteTarget.Cellular, routeCandidates = listOf(wifi))
        }
        assertFailsWith<IllegalArgumentException> {
            ready(routeCandidates = listOf(unavailableWifi))
        }
        assertFailsWith<IllegalArgumentException> {
            ready(listenHost = "127.0.0.1", hasHighSecurityRisk = true, routeCandidates = listOf(wifi))
        }
    }

    private fun assertStartupFailure(
        config: AppConfig,
        expected: ProxyStartupError,
    ) {
        val failed =
            assertIs<ProxyServiceStartupDecision.Failed>(
                ProxyServiceStartupPolicy.evaluate(
                    config = config,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", true)),
                ),
            )

        assertEquals(expected, failed.startupError)
        assertEquals(expected, failed.status.startupError)
        assertEquals(config.network.defaultRoutePolicy, failed.status.configuredRoute)
    }

    private fun ready(
        listenHost: String = "127.0.0.1",
        configuredRoute: RouteTarget = RouteTarget.WiFi,
        routeCandidates: List<NetworkDescriptor> =
            listOf(NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", true)),
        hasHighSecurityRisk: Boolean = false,
    ): ProxyServiceStartupDecision.Ready = ProxyServiceStartupDecision.Ready(
        listenHost = listenHost,
        listenPort = 8080,
        configuredRoute = configuredRoute,
        routeCandidates = routeCandidates,
        hasHighSecurityRisk = hasHighSecurityRisk,
    )
}
