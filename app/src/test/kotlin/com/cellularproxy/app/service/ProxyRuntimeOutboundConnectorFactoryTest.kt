package com.cellularproxy.app.service

import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.network.BoundSocketProvider
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyRuntimeOutboundConnectorFactoryTest {
    @Test
    fun `http connector opens selected route socket for parsed origin`() {
        ConnectedSocketPair.open().use { socketPair ->
            val provider =
                RecordingBoundSocketProvider(
                    result = BoundSocketConnectResult.Connected(socketPair.client, wifiRoute()),
                )
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.WiFi,
                    socketProvider = provider,
                    connectTimeoutMillis = 2_500,
                )

            val openResult =
                connectors.httpConnector.open(
                    ParsedProxyRequest.HttpProxy(
                        method = "GET",
                        host = "example.com",
                        port = 8080,
                        originTarget = "/resource",
                    ),
                )

            val connected = assertIs<OutboundHttpOriginOpenResult.Connected>(openResult)
            assertEquals("example.com", connected.connection.host)
            assertEquals(8080, connected.connection.port)
            assertEquals(
                listOf(ConnectCall(RouteTarget.WiFi, "example.com", 8080, 2_500)),
                provider.calls,
            )
        }
    }

    @Test
    fun `connect tunnel connector opens selected route socket for parsed authority`() {
        ConnectedSocketPair.open().use { socketPair ->
            val provider =
                RecordingBoundSocketProvider(
                    result = BoundSocketConnectResult.Connected(socketPair.client, vpnRoute()),
                )
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.Vpn,
                    socketProvider = provider,
                    connectTimeoutMillis = 5_000,
                )

            val openResult =
                connectors.connectConnector.open(
                    ParsedProxyRequest.ConnectTunnel(
                        host = "api.example.test",
                        port = 443,
                    ),
                )

            val connected = assertIs<OutboundConnectTunnelOpenResult.Connected>(openResult)
            assertEquals("api.example.test", connected.connection.host)
            assertEquals(443, connected.connection.port)
            assertEquals(
                listOf(ConnectCall(RouteTarget.Vpn, "api.example.test", 443, 5_000)),
                provider.calls,
            )
        }
    }

    @Test
    fun `interrupted http connector closes late connected sockets`() {
        ConnectedSocketPair.open().use { socketPair ->
            val provider = SuspendedBoundSocketProvider()
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.WiFi,
                    socketProvider = provider,
                )
            val executor = Executors.newSingleThreadExecutor()
            val workerThread = AtomicReference<Thread>()
            val future =
                executor.submit<OutboundHttpOriginOpenResult> {
                    workerThread.set(Thread.currentThread())
                    connectors.httpConnector.open(
                        ParsedProxyRequest.HttpProxy(
                            method = "GET",
                            host = "example.com",
                            port = 80,
                            originTarget = "/",
                        ),
                    )
                }

            try {
                assertTrue(provider.awaitStarted())
                workerThread.get().interrupt()
                assertEquals(
                    OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.OutboundConnectionFailed),
                    future.get(1, TimeUnit.SECONDS),
                )

                provider.resumeWith(BoundSocketConnectResult.Connected(socketPair.client, wifiRoute()))

                assertTrue(socketPair.client.isClosed)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `interrupted connect tunnel connector returns failure instead of crashing worker thread`() {
        ConnectedSocketPair.open().use { socketPair ->
            val provider = SuspendedBoundSocketProvider()
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.Cellular,
                    socketProvider = provider,
                )
            val executor = Executors.newSingleThreadExecutor()
            val workerThread = AtomicReference<Thread>()
            val future =
                executor.submit<OutboundConnectTunnelOpenResult> {
                    workerThread.set(Thread.currentThread())
                    connectors.connectConnector.open(
                        ParsedProxyRequest.ConnectTunnel(
                            host = "example.com",
                            port = 443,
                        ),
                    )
                }

            try {
                assertTrue(provider.awaitStarted())
                workerThread.get().interrupt()
                assertEquals(
                    OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.OutboundConnectionFailed),
                    future.get(1, TimeUnit.SECONDS),
                )

                provider.resumeWith(BoundSocketConnectResult.Connected(socketPair.client, wifiRoute()))

                assertTrue(socketPair.client.isClosed)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `http connector maps bound socket failures to proxy origin failures`() {
        val cases =
            mapOf(
                BoundSocketConnectFailure.SelectedRouteUnavailable to OutboundHttpOriginOpenFailure.SelectedRouteUnavailable,
                BoundSocketConnectFailure.DnsResolutionFailed to OutboundHttpOriginOpenFailure.DnsResolutionFailed,
                BoundSocketConnectFailure.ConnectionFailed to OutboundHttpOriginOpenFailure.OutboundConnectionFailed,
                BoundSocketConnectFailure.ConnectionTimedOut to OutboundHttpOriginOpenFailure.OutboundConnectionTimeout,
            )

        cases.forEach { (boundFailure, expectedProxyFailure) ->
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.Cellular,
                    socketProvider =
                        RecordingBoundSocketProvider(
                            result = BoundSocketConnectResult.Failed(boundFailure),
                        ),
                )

            val openResult =
                connectors.httpConnector.open(
                    ParsedProxyRequest.HttpProxy(
                        method = "GET",
                        host = "example.com",
                        port = 80,
                        originTarget = "/",
                    ),
                )

            assertEquals(
                OutboundHttpOriginOpenResult.Failed(expectedProxyFailure),
                openResult,
            )
        }
    }

    @Test
    fun `connect tunnel connector maps bound socket failures to proxy tunnel failures`() {
        val cases =
            mapOf(
                BoundSocketConnectFailure.SelectedRouteUnavailable to OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable,
                BoundSocketConnectFailure.DnsResolutionFailed to OutboundConnectTunnelOpenFailure.DnsResolutionFailed,
                BoundSocketConnectFailure.ConnectionFailed to OutboundConnectTunnelOpenFailure.OutboundConnectionFailed,
                BoundSocketConnectFailure.ConnectionTimedOut to OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout,
            )

        cases.forEach { (boundFailure, expectedProxyFailure) ->
            val connectors =
                ProxyRuntimeOutboundConnectorFactory.create(
                    route = RouteTarget.Automatic,
                    socketProvider =
                        RecordingBoundSocketProvider(
                            result = BoundSocketConnectResult.Failed(boundFailure),
                        ),
                )

            val openResult =
                connectors.connectConnector.open(
                    ParsedProxyRequest.ConnectTunnel(
                        host = "example.com",
                        port = 443,
                    ),
                )

            assertEquals(
                OutboundConnectTunnelOpenResult.Failed(expectedProxyFailure),
                openResult,
            )
        }
    }

    @Test
    fun `connector factory rejects invalid connect timeouts`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyRuntimeOutboundConnectorFactory.create(
                route = RouteTarget.WiFi,
                socketProvider =
                    RecordingBoundSocketProvider(
                        result = BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable),
                    ),
                connectTimeoutMillis = 0,
            )
        }
    }

    private class RecordingBoundSocketProvider(
        private val result: BoundSocketConnectResult,
    ) : BoundSocketProvider {
        val calls = mutableListOf<ConnectCall>()

        override suspend fun connect(
            route: RouteTarget,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult {
            calls += ConnectCall(route, host, port, timeoutMillis)
            return result
        }
    }

    private class SuspendedBoundSocketProvider : BoundSocketProvider {
        private val started = java.util.concurrent.CountDownLatch(1)
        private val continuation = AtomicReference<Continuation<BoundSocketConnectResult>>()

        override suspend fun connect(
            route: RouteTarget,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult = suspendCoroutine { continuation ->
            this.continuation.set(continuation)
            started.countDown()
        }

        fun awaitStarted(): Boolean = started.await(1, TimeUnit.SECONDS)

        fun resumeWith(result: BoundSocketConnectResult) {
            continuation.get().resume(result)
        }
    }

    private data class ConnectCall(
        val route: RouteTarget,
        val host: String,
        val port: Int,
        val timeoutMillis: Long,
    )

    private class ConnectedSocketPair private constructor(
        val client: Socket,
        private val serverSide: Socket,
        private val acceptExecutor: java.util.concurrent.ExecutorService,
    ) : AutoCloseable {
        override fun close() {
            client.close()
            serverSide.close()
            acceptExecutor.shutdownNow()
            assertTrue(acceptExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }

        companion object {
            fun open(): ConnectedSocketPair {
                ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    val acceptExecutor = Executors.newSingleThreadExecutor()
                    val acceptedFuture = acceptExecutor.submit<Socket> { server.accept() }
                    val client = Socket("127.0.0.1", server.localPort)
                    val serverSide = acceptedFuture.get(1, TimeUnit.SECONDS)
                    return ConnectedSocketPair(client, serverSide, acceptExecutor)
                }
            }
        }
    }

    private fun wifiRoute(): NetworkDescriptor = NetworkDescriptor(
        id = "wifi",
        category = NetworkCategory.WiFi,
        displayName = "Home Wi-Fi",
        isAvailable = true,
    )

    private fun vpnRoute(): NetworkDescriptor = NetworkDescriptor(
        id = "vpn",
        category = NetworkCategory.Vpn,
        displayName = "Tailscale",
        isAvailable = true,
    )
}
