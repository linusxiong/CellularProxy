package com.cellularproxy.proxy.server

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyStartupError
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyServerRuntimeStartupTest {
    @Test
    fun `startup preflight failure returns failed status without binding listener`() {
        var bindCalled = false
        val invalidConfig =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = "localhost"),
            )

        val failed =
            assertIs<ProxyServerRuntimeStartupResult.Failed>(
                ProxyServerRuntimeStartup.start(
                    config = invalidConfig,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    bindListener = { _, _, _ ->
                        bindCalled = true
                        error("invalid startup must not attempt listener binding")
                    },
                ),
            )

        assertFalse(bindCalled)
        assertEquals(ProxyStartupError.InvalidListenAddress, failed.startupError)
        assertEquals(ProxyServiceState.Failed, failed.status.state)
        assertEquals(ProxyStartupError.InvalidListenAddress, failed.status.startupError)
    }

    @Test
    fun `bind failure returns structured failed startup status`() {
        val config =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                network = AppConfig.default().network.copy(defaultRoutePolicy = RouteTarget.WiFi),
            )

        val failed =
            assertIs<ProxyServerRuntimeStartupResult.Failed>(
                ProxyServerRuntimeStartup.start(
                    config = config,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    bindListener = { host, port, backlog ->
                        assertEquals(LOOPBACK_HOST, host)
                        assertEquals(8081, port)
                        assertEquals(25, backlog)
                        ProxyServerSocketBindResult.Failed(ProxyStartupError.PortAlreadyInUse)
                    },
                    backlog = 25,
                ),
            )

        assertEquals(ProxyStartupError.PortAlreadyInUse, failed.startupError)
        assertEquals(ProxyServiceState.Failed, failed.status.state)
        assertEquals(ProxyStartupError.PortAlreadyInUse, failed.status.startupError)
        assertEquals(RouteTarget.WiFi, failed.status.configuredRoute)
    }

    @Test
    fun `successful startup returns bound listener and running status`() {
        val wifi = wifiRoute()
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val config =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                network = AppConfig.default().network.copy(defaultRoutePolicy = RouteTarget.WiFi),
            )

        try {
            val started =
                assertIs<ProxyServerRuntimeStartupResult.Started>(
                    ProxyServerRuntimeStartup.start(
                        config = config,
                        managementApiTokenPresent = true,
                        observedNetworks = listOf(wifi),
                        bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
                    ),
                )

            assertTrue(started.listener === listener)
            assertEquals(ProxyServiceState.Running, started.status.state)
            assertEquals(LOOPBACK_HOST, started.status.listenHost)
            assertEquals(listener.listenPort, started.status.listenPort)
            assertEquals(RouteTarget.WiFi, started.status.configuredRoute)
            assertEquals(wifi, started.status.boundRoute)
            assertNull(started.status.publicIp)
            assertFalse(started.status.hasHighSecurityRisk)
            assertNull(started.status.startupError)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `successful broad unauthenticated startup preserves high risk status`() {
        val wifi = wifiRoute()
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, BROAD_HOST)
        val config =
            AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(authEnabled = false),
            )

        try {
            val started =
                assertIs<ProxyServerRuntimeStartupResult.Started>(
                    ProxyServerRuntimeStartup.start(
                        config = config,
                        managementApiTokenPresent = true,
                        observedNetworks = listOf(wifi),
                        bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
                    ),
                )

            assertEquals(BROAD_HOST, started.status.listenHost)
            assertTrue(started.status.hasHighSecurityRisk)
        } finally {
            listener.close()
        }
    }

    private fun wifiRoute(): NetworkDescriptor = NetworkDescriptor(
        id = "wifi",
        category = NetworkCategory.WiFi,
        displayName = "Home Wi-Fi",
        isAvailable = true,
    )
}

private const val LOOPBACK_HOST = "127.0.0.1"
private const val BROAD_HOST = "0.0.0.0"
