package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteBoundPublicIpProbeTest {
    @Test
    fun `probes public IP through selected route with minimal HTTP request`() {
        val network = network("cell", NetworkCategory.Cellular)
        scriptedSocketServer(
            response = "HTTP/1.1 200 OK\r\nContent-Length: 12\r\nConnection: close\r\n\r\n203.0.113.7\n",
        ).use { server ->
            val provider = RecordingBoundSocketProvider(
                BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
            )
            val probe = RouteBoundPublicIpProbe(provider)

            val result = runSuspend {
                probe.probe(
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(
                        host = "ip.example",
                        port = 8080,
                        path = "/check",
                        timeoutMillis = 5_000,
                    ),
                )
            }

            val success = assertIs<PublicIpProbeResult.Success>(result)
            assertEquals("203.0.113.7", success.publicIp)
            assertEquals(network, success.network)
            assertEquals(
                listOf(BoundConnectCall(RouteTarget.Cellular, "ip.example", 8080, 5_000)),
                provider.calls,
            )
            assertContains(server.requestText(), "GET /check HTTP/1.1\r\n")
            assertContains(server.requestText(), "Host: ip.example:8080\r\n")
            assertContains(server.requestText(), "Connection: close\r\n")
        }
    }

    @Test
    fun `uses plain host header for default HTTP port`() {
        val network = network("wifi", NetworkCategory.WiFi)
        scriptedSocketServer(
            response = "HTTP/1.1 200 OK\r\n\r\n198.51.100.42",
        ).use { server ->
            val provider = RecordingBoundSocketProvider(
                BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
            )

            val result = runSuspend {
                RouteBoundPublicIpProbe(provider).probe(
                    route = RouteTarget.WiFi,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                )
            }

            assertIs<PublicIpProbeResult.Success>(result)
            assertContains(server.requestText(), "Host: ip.example\r\n")
        }
    }

    @Test
    fun `formats IPv6 literal host header with brackets`() {
        val network = network("wifi", NetworkCategory.WiFi)
        scriptedSocketServer(
            response = "HTTP/1.1 200 OK\r\n\r\n198.51.100.42",
        ).use { server ->
            val provider = RecordingBoundSocketProvider(
                BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
            )

            val result = runSuspend {
                RouteBoundPublicIpProbe(provider).probe(
                    route = RouteTarget.WiFi,
                    endpoint = PublicIpProbeEndpoint(host = "2001:db8::1", port = 8080),
                )
            }

            assertIs<PublicIpProbeResult.Success>(result)
            assertContains(server.requestText(), "Host: [2001:db8::1]:8080\r\n")
        }
    }

    @Test
    fun `maps selected route connect failure without opening probe request`() {
        val provider = RecordingBoundSocketProvider(
            BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable),
        )

        val result = runSuspend {
            RouteBoundPublicIpProbe(provider).probe(
                route = RouteTarget.Vpn,
                endpoint = PublicIpProbeEndpoint(host = "ip.example"),
            )
        }

        val failed = assertIs<PublicIpProbeResult.Failed>(result)
        assertEquals(PublicIpProbeFailure.SelectedRouteUnavailable, failed.reason)
        assertEquals(listOf(BoundConnectCall(RouteTarget.Vpn, "ip.example", 80, 5_000)), provider.calls)
    }

    @Test
    fun `rejects non-success HTTP response`() {
        val network = network("wifi", NetworkCategory.WiFi)
        scriptedSocketServer(
            response = "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\ntry later",
        ).use { server ->
            val result = runSuspend {
                RouteBoundPublicIpProbe(
                    RecordingBoundSocketProvider(
                        BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
                    ),
                ).probe(
                    route = RouteTarget.WiFi,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                )
            }

            val failed = assertIs<PublicIpProbeResult.Failed>(result)
            assertEquals(PublicIpProbeFailure.NonSuccessStatus, failed.reason)
            assertEquals(network, failed.network)
        }
    }

    @Test
    fun `rejects response body that is not a numeric public IP`() {
        val network = network("wifi", NetworkCategory.WiFi)
        scriptedSocketServer(
            response = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nnot an ip",
        ).use { server ->
            val result = runSuspend {
                RouteBoundPublicIpProbe(
                    RecordingBoundSocketProvider(
                        BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
                    ),
                ).probe(
                    route = RouteTarget.WiFi,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                )
            }

            val failed = assertIs<PublicIpProbeResult.Failed>(result)
            assertEquals(PublicIpProbeFailure.InvalidPublicIp, failed.reason)
            assertEquals(network, failed.network)
        }
    }

    @Test
    fun `accepts IPv6 public IP response bodies`() {
        val network = network("wifi", NetworkCategory.WiFi)
        scriptedSocketServer(
            response = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n2001:db8::2\n",
        ).use { server ->
            val result = runSuspend {
                RouteBoundPublicIpProbe(
                    RecordingBoundSocketProvider(
                        BoundSocketConnectResult.Connected(socket = server.connectClient(), network = network),
                    ),
                ).probe(
                    route = RouteTarget.WiFi,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                )
            }

            val success = assertIs<PublicIpProbeResult.Success>(result)
            assertEquals("2001:db8::2", success.publicIp)
        }
    }

    @Test
    fun `rejects invalid probe endpoint values`() {
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip example") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example\r\nInjected: value") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "éxample.com") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "[2001:db8::1]") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", port = 0) }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", path = "check") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", path = "/bad path") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", path = "/é") }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", timeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { PublicIpProbeEndpoint(host = "ip.example", maxResponseBytes = 0) }
    }

    @Test
    fun `failed probe result rejects unavailable bound network metadata`() {
        assertFailsWith<IllegalArgumentException> {
            PublicIpProbeResult.Failed(
                reason = PublicIpProbeFailure.InvalidPublicIp,
                network = NetworkDescriptor(
                    id = "wifi",
                    category = NetworkCategory.WiFi,
                    displayName = "wifi",
                    isAvailable = false,
                ),
            )
        }
    }

    private class RecordingBoundSocketProvider(
        private val result: BoundSocketConnectResult,
    ) : BoundSocketProvider {
        val calls = mutableListOf<BoundConnectCall>()

        override suspend fun connect(
            route: RouteTarget,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult {
            calls += BoundConnectCall(route, host, port, timeoutMillis)
            return result
        }
    }

    private data class BoundConnectCall(
        val route: RouteTarget,
        val host: String,
        val port: Int,
        val timeoutMillis: Long,
    )

    private fun network(
        id: String,
        category: NetworkCategory,
    ): NetworkDescriptor =
        NetworkDescriptor(
            id = id,
            category = category,
            displayName = id,
            isAvailable = true,
        )

    private fun scriptedSocketServer(response: String): ScriptedSocketServer =
        ScriptedSocketServer(response.toByteArray(Charsets.US_ASCII))

    private class ScriptedSocketServer(
        private val response: ByteArray,
    ) : Closeable {
        private val server = ServerSocket(0)
        private val capturedRequest = ByteArrayOutputStream()
        private var failure: Throwable? = null
        private val thread = thread(start = true) {
            try {
                server.accept().use { socket ->
                    readHeaders(socket)
                    socket.getOutputStream().write(response)
                    socket.getOutputStream().flush()
                }
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }

        fun connectClient(): Socket = Socket("127.0.0.1", server.localPort)

        fun requestText(): String {
            thread.join(1_000)
            failure?.let { throw it }
            return capturedRequest.toString(Charsets.US_ASCII)
        }

        private fun readHeaders(socket: Socket) {
            val input = socket.getInputStream()
            var previous = -1
            while (true) {
                val current = input.read()
                if (current == -1) {
                    return
                }
                capturedRequest.write(current)
                if (previous == '\n'.code && current == '\n'.code) {
                    return
                }
                val request = capturedRequest.toByteArray()
                if (
                    request.size >= 4 &&
                    request[request.size - 4] == '\r'.code.toByte() &&
                    request[request.size - 3] == '\n'.code.toByte() &&
                    request[request.size - 2] == '\r'.code.toByte() &&
                    request[request.size - 1] == '\n'.code.toByte()
                ) {
                    return
                }
                previous = current
            }
        }

        override fun close() {
            server.close()
            thread.join(1_000)
            failure?.let { throw it }
        }
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
}
