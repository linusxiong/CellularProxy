package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouteBoundSocketProviderTest {
    @Test
    fun `explicit route connects through matching available network`() {
        val wifi = network("wifi", NetworkCategory.WiFi)
        val cellular = network("cell", NetworkCategory.Cellular)
        connectedSocketPair().use { socketPair ->
            val calls = mutableListOf<ConnectCall>()
            val provider = RouteBoundSocketProvider(
                observedNetworks = { listOf(wifi, cellular) },
                connector = recordingConnector(calls) {
                    BoundSocketConnectResult.Connected(socket = socketPair.client, network = it.network)
                },
            )

            val result = runSuspend {
                provider.connect(
                    route = RouteTarget.Cellular,
                    host = "example.com",
                    port = 443,
                    timeoutMillis = 5_000,
                )
            }

            val connected = assertIs<BoundSocketConnectResult.Connected>(result)
            assertSame(socketPair.client, connected.socket)
            assertEquals(cellular, connected.network)
            assertEquals(listOf(ConnectCall(cellular, "example.com", 443, 5_000)), calls)
        }
    }

    @Test
    fun `automatic route uses first available network in monitor order`() {
        val unavailableWifi = network("wifi", NetworkCategory.WiFi, isAvailable = false)
        val vpn = network("vpn", NetworkCategory.Vpn)
        val cellular = network("cell", NetworkCategory.Cellular)
        connectedSocketPair().use { socketPair ->
            val calls = mutableListOf<ConnectCall>()
            val provider = RouteBoundSocketProvider(
                observedNetworks = { listOf(unavailableWifi, vpn, cellular) },
                connector = recordingConnector(calls) {
                    BoundSocketConnectResult.Connected(socket = socketPair.client, network = it.network)
                },
            )

            val result = runSuspend {
                provider.connect(
                    route = RouteTarget.Automatic,
                    host = "origin.test",
                    port = 80,
                    timeoutMillis = 1_000,
                )
            }

            val connected = assertIs<BoundSocketConnectResult.Connected>(result)
            assertEquals(vpn, connected.network)
            assertEquals(listOf(ConnectCall(vpn, "origin.test", 80, 1_000)), calls)
        }
    }

    @Test
    fun `unavailable selected route fails before connector is called`() {
        var connectorCalled = false
        val provider = RouteBoundSocketProvider(
            observedNetworks = { listOf(network("wifi", NetworkCategory.WiFi)) },
            connector = BoundNetworkSocketConnector { _, _, _, _ ->
                connectorCalled = true
                BoundSocketConnectResult.Connected(socket = Socket(), network = network("cell", NetworkCategory.Cellular))
            },
        )

        val result = runSuspend {
            provider.connect(
                route = RouteTarget.Cellular,
                host = "example.com",
                port = 443,
                timeoutMillis = 5_000,
            )
        }

        val failed = assertIs<BoundSocketConnectResult.Failed>(result)
        assertEquals(BoundSocketConnectFailure.SelectedRouteUnavailable, failed.reason)
        assertFalse(connectorCalled)
    }

    @Test
    fun `connector failure is preserved`() {
        val wifi = network("wifi", NetworkCategory.WiFi)
        val provider = RouteBoundSocketProvider(
            observedNetworks = { listOf(wifi) },
            connector = BoundNetworkSocketConnector { _, _, _, _ ->
                BoundSocketConnectResult.Failed(BoundSocketConnectFailure.ConnectionTimedOut)
            },
        )

        val result = runSuspend {
            provider.connect(
                route = RouteTarget.WiFi,
                host = "example.com",
                port = 443,
                timeoutMillis = 5_000,
            )
        }

        val failed = assertIs<BoundSocketConnectResult.Failed>(result)
        assertEquals(BoundSocketConnectFailure.ConnectionTimedOut, failed.reason)
    }

    @Test
    fun `connector connected result must match the selected network and closes mismatched socket`() {
        val wifi = network("wifi", NetworkCategory.WiFi)
        val cellular = network("cell", NetworkCategory.Cellular)
        connectedSocketPair().use { socketPair ->
            val provider = RouteBoundSocketProvider(
                observedNetworks = { listOf(wifi, cellular) },
                connector = BoundNetworkSocketConnector { _, _, _, _ ->
                    BoundSocketConnectResult.Connected(
                        socket = socketPair.client,
                        network = cellular,
                    )
                },
            )

            assertFailsWithMessage("Connector returned a socket for a different network") {
                runSuspend {
                    provider.connect(
                        route = RouteTarget.WiFi,
                        host = "example.com",
                        port = 443,
                        timeoutMillis = 5_000,
                    )
                }
            }
            assertTrue(socketPair.client.isClosed)
        }
    }

    @Test
    fun `invalid connection arguments fail before observing networks`() {
        var observedNetworksCalled = false
        val provider = RouteBoundSocketProvider(
            observedNetworks = {
                observedNetworksCalled = true
                listOf(network("wifi", NetworkCategory.WiFi))
            },
            connector = BoundNetworkSocketConnector { _, _, _, _ ->
                error("Connector should not be called for invalid arguments")
            },
        )

        assertFailsWithMessage("Host must not be blank") {
            runSuspend {
                provider.connect(
                    route = RouteTarget.WiFi,
                    host = " ",
                    port = 443,
                    timeoutMillis = 5_000,
                )
            }
        }
        assertFailsWithMessage("Port must be in range 1..65535") {
            runSuspend {
                provider.connect(
                    route = RouteTarget.WiFi,
                    host = "example.com",
                    port = 0,
                    timeoutMillis = 5_000,
                )
            }
        }
        assertFailsWithMessage("Timeout must be positive") {
            runSuspend {
                provider.connect(
                    route = RouteTarget.WiFi,
                    host = "example.com",
                    port = 443,
                    timeoutMillis = 0,
                )
            }
        }
        assertFalse(observedNetworksCalled)
    }

    @Test
    fun `connected result must carry selected network and open socket`() {
        assertFailsWithMessage("Connected socket must not be closed") {
            connectedSocketPair().use { socketPair ->
                BoundSocketConnectResult.Connected(
                    socket = socketPair.client.also(Socket::close),
                    network = network("wifi", NetworkCategory.WiFi),
                )
            }
        }
        assertFailsWithMessage("Connected socket must already be connected") {
            BoundSocketConnectResult.Connected(
                socket = Socket(),
                network = network("wifi", NetworkCategory.WiFi),
            )
        }
        assertFailsWithMessage("Connected network must be available") {
            connectedSocketPair().use { socketPair ->
                BoundSocketConnectResult.Connected(
                    socket = socketPair.client,
                    network = network("wifi", NetworkCategory.WiFi, isAvailable = false),
                )
            }
        }
    }

    private fun network(
        id: String,
        category: NetworkCategory,
        isAvailable: Boolean = true,
    ): NetworkDescriptor =
        NetworkDescriptor(
            id = id,
            category = category,
            displayName = id,
            isAvailable = isAvailable,
        )

    private fun recordingConnector(
        calls: MutableList<ConnectCall>,
        result: (ConnectCall) -> BoundSocketConnectResult,
    ): BoundNetworkSocketConnector =
        BoundNetworkSocketConnector { network, host, port, timeoutMillis ->
            val call = ConnectCall(network, host, port, timeoutMillis)
            calls += call
            result(call)
        }

    private fun assertFailsWithMessage(
        message: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertEquals(message, failure.message)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed = false
        var value: T? = null
        var failure: Throwable? = null

        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    completed = true
                    result
                        .onSuccess { value = it }
                        .onFailure { failure = it }
                }
            },
        )

        assertTrue(completed, "Test suspend block must complete synchronously")
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    data class ConnectCall(
        val network: NetworkDescriptor,
        val host: String,
        val port: Int,
        val timeoutMillis: Long,
    )

    private fun connectedSocketPair(): ConnectedSocketPair {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort)
            val serverSide = server.accept()
            return ConnectedSocketPair(client, serverSide)
        }
    }

    private data class ConnectedSocketPair(
        val client: Socket,
        val serverSide: Socket,
    ) : Closeable {
        override fun close() {
            closeSocketQuietly(client)
            closeSocketQuietly(serverSide)
        }
    }

    companion object {
        private fun closeSocketQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Test cleanup should not hide assertion failures.
            }
        }
    }
}
