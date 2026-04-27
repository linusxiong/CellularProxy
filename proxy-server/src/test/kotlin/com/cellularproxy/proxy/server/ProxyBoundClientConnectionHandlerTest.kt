package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnection
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyBoundClientConnectionHandlerTest {
    private val config =
        ProxyIngressPreflightConfig(
            connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 1),
            requestAdmission =
                ProxyRequestAdmissionConfig(
                    proxyAuthentication =
                        ProxyAuthenticationConfig(
                            authEnabled = false,
                            credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                        ),
                    managementApiToken = MANAGEMENT_TOKEN,
                ),
        )

    @Test
    fun `overlapping accepted sockets contribute to connection-limit admission`() {
        val listener =
            assertIs<ProxyServerSocketBindResult.Bound>(
                ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
            ).listener
        val blockingManagementHandler = BlockingManagementHandler()
        val server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler = exchangeHandler(managementHandler = blockingManagementHandler),
            )
        val metricEvents = CopyOnWriteArrayList<ProxyTrafficMetricsEvent>()
        val firstServerFailure = AtomicReference<Throwable?>()
        val firstClientFailure = AtomicReference<Throwable?>()

        listener.use {
            val firstClientSocket = Socket(LOOPBACK_HOST, listener.listenPort)
            val firstServer =
                thread(start = true) {
                    firstServerFailure.capture {
                        val result =
                            server.handleNext(
                                listener = listener,
                                config = config,
                                recordMetricEvent = { metricEvents.add(it) },
                            )
                        assertEquals(0, result.activeConnectionsBeforeAdmission)
                        assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result.exchange)
                    }
                }

            assertTrue(
                metricEvents.awaitEvents(
                    listOf<ProxyTrafficMetricsEvent>(ProxyTrafficMetricsEvent.ConnectionAccepted),
                ),
                "accepted socket should be visible in metrics before header preflight completes",
            )
            assertEquals(1, server.activeClientConnections)

            val firstClient =
                thread(start = true) {
                    firstClientFailure.capture {
                        firstClientSocket.use { socket ->
                            socket.getOutputStream().write(managementRequestBytes())
                            socket.getOutputStream().flush()
                            assertEquals(
                                "HTTP/1.1 202 Accepted",
                                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                            )
                        }
                    }
                }

            assertTrue(blockingManagementHandler.awaitStarted(), "first request should reach management handler")
            assertEquals(1, server.activeClientConnections)

            Socket(LOOPBACK_HOST, listener.listenPort).use { secondClient ->
                secondClient.getOutputStream().write(managementRequestBytes())
                secondClient.getOutputStream().flush()

                val secondResult =
                    server.handleNext(
                        listener = listener,
                        config = config,
                        recordMetricEvent = { metricEvents.add(it) },
                    )

                assertEquals(1, secondResult.activeConnectionsBeforeAdmission)
                val rejected =
                    assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(
                        secondResult.exchange,
                    )
                assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
                assertEquals(
                    "HTTP/1.1 503 Service Unavailable",
                    BufferedReader(InputStreamReader(secondClient.getInputStream(), Charsets.US_ASCII)).readLine(),
                )
            }

            assertEquals(1, server.activeClientConnections)
            blockingManagementHandler.release()
            firstServer.join(1_000)
            firstClient.join(1_000)
            assertTrue(!firstServer.isAlive, "first server handler should finish after release")
            assertTrue(!firstClient.isAlive, "first client should finish after release")
            firstServerFailure.get()?.let { throw it }
            firstClientFailure.get()?.let { throw it }
            assertEquals(0, server.activeClientConnections)
            assertEquals(
                listOf<ProxyTrafficMetricsEvent>(
                    ProxyTrafficMetricsEvent.ConnectionAccepted,
                    ProxyTrafficMetricsEvent.BytesReceived(managementRequestBytes().size.toLong()),
                    ProxyTrafficMetricsEvent.ConnectionAccepted,
                    ProxyTrafficMetricsEvent.ConnectionRejected,
                    ProxyTrafficMetricsEvent.BytesSent(136),
                    ProxyTrafficMetricsEvent.ConnectionClosed,
                    ProxyTrafficMetricsEvent.BytesSent(153),
                    ProxyTrafficMetricsEvent.ConnectionClosed,
                ),
                metricEvents,
            )
        }
    }

    @Test
    fun `active connection count is decremented when exchange handling throws`() {
        val listener =
            assertIs<ProxyServerSocketBindResult.Bound>(
                ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
            ).listener
        val server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler = exchangeHandler(managementHandler = ThrowingManagementHandler()),
            )

        listener.use {
            Socket(LOOPBACK_HOST, listener.listenPort).use { socket ->
                socket.getOutputStream().write(managementRequestBytes())
                socket.getOutputStream().flush()

                assertFailsWith<IllegalArgumentException> {
                    server.handleNext(
                        listener = listener,
                        config = config,
                        httpBufferSize = 0,
                    )
                }
            }
        }

        assertEquals(0, server.activeClientConnections)
    }

    @Test
    fun `active connection count is decremented before close metric callback runs`() {
        val listener =
            assertIs<ProxyServerSocketBindResult.Bound>(
                ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
            ).listener
        val server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler =
                    exchangeHandler(
                        managementHandler =
                            ManagementApiHandler {
                                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}""")
                            },
                    ),
            )
        val activeConnectionsObservedOnClose = AtomicReference<Long>()

        listener.use {
            Socket(LOOPBACK_HOST, listener.listenPort).use { socket ->
                socket.getOutputStream().write(managementRequestBytes())
                socket.getOutputStream().flush()

                server.handleNext(
                    listener = listener,
                    config = config,
                    recordMetricEvent = { event ->
                        if (event == ProxyTrafficMetricsEvent.ConnectionClosed) {
                            activeConnectionsObservedOnClose.set(server.activeClientConnections)
                        }
                    },
                )
            }
        }

        assertEquals(0, activeConnectionsObservedOnClose.get())
        assertEquals(0, server.activeClientConnections)
    }

    @Test
    fun `active proxy exchange count tracks admitted HTTP traffic separately from socket reservations`() {
        lateinit var server: ProxyBoundClientConnectionHandler
        val originBodyReadStarted = CountDownLatch(1)
        val releaseOriginBody = CountDownLatch(1)
        val serverResult = AtomicReference<ProxyBoundClientConnectionHandlingResult?>()
        val serverFailure = AtomicReference<Throwable?>()
        val httpConnector =
            OutboundHttpOriginConnector { request ->
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input =
                            BlockingHttpResponseBodyInputStream(
                                bodyReadStarted = originBodyReadStarted,
                                releaseBody = releaseOriginBody,
                            ),
                        output = ByteArrayOutputStream(),
                        host = request.host,
                        port = request.port,
                    ),
                )
            }
        server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler =
                    ProxyClientStreamExchangeHandler(
                        httpConnector = httpConnector,
                        connectConnector = ThrowingConnectConnector(),
                        managementHandler = ThrowingManagementHandler(),
                    ),
            )
        val clientOutput = ByteArrayOutputStream()

        val serverThread =
            thread(start = true) {
                serverFailure.capture {
                    serverResult.set(
                        server.handleAccepted(
                            client =
                                ProxyClientStreamConnection(
                                    input = ByteArrayInputStream(httpProxyRequestBytes()),
                                    output = clientOutput,
                                ),
                            config = config,
                        ),
                    )
                }
            }

        assertTrue(originBodyReadStarted.await(1, TimeUnit.SECONDS), "HTTP forwarding should reach response body")
        assertEquals(1, server.activeProxyExchanges)
        assertEquals(1, server.activeClientConnections)

        releaseOriginBody.countDown()
        serverThread.join(1_000)
        assertTrue(!serverThread.isAlive, "server handler should finish after origin body release")
        serverFailure.get()?.let { throw it }
        assertIs<ProxyClientStreamExchangeHandlingResult.HttpProxyHandled>(
            serverResult.get()?.exchange ?: error("server result should be recorded"),
        )
        assertContains(clientOutput.toString(Charsets.UTF_8), "HTTP/1.1 200 OK")
        assertContains(clientOutput.toString(Charsets.UTF_8), "BODY")
        assertEquals(0, server.activeProxyExchanges)
        assertEquals(0, server.activeClientConnections)
    }

    @Test
    fun `header read idle timeout emits safe response and releases active connection`() {
        val listener =
            assertIs<ProxyServerSocketBindResult.Bound>(
                ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
            ).listener
        val server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler = exchangeHandler(managementHandler = ThrowingManagementHandler()),
            )
        val metricEvents = CopyOnWriteArrayList<ProxyTrafficMetricsEvent>()
        val serverResult = AtomicReference<ProxyBoundClientConnectionHandlingResult?>()
        val serverFailure = AtomicReference<Throwable?>()

        listener.use {
            Socket(LOOPBACK_HOST, listener.listenPort).use { client ->
                client.soTimeout = CLIENT_TEST_READ_TIMEOUT_MILLIS
                val serverThread =
                    thread(start = true) {
                        serverFailure.capture {
                            serverResult.set(
                                server.handleNext(
                                    listener = listener,
                                    config = config,
                                    clientHeaderReadIdleTimeoutMillis = 50,
                                    recordMetricEvent = { metricEvents.add(it) },
                                ),
                            )
                        }
                    }

                assertEquals(
                    "HTTP/1.1 408 Request Timeout",
                    BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII)).readLine(),
                )

                serverThread.join(1_000)
                assertTrue(!serverThread.isAlive, "server handler should finish after the idle timeout response")
                serverFailure.get()?.let { throw it }
            }
        }

        val result = serverResult.get() ?: error("server result should be recorded")
        val rejected = assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(result.exchange)
        assertIs<ProxyServerFailure.IdleTimeout>(rejected.failure)
        assertEquals(0, rejected.headerBytesRead)
        assertEquals(0, server.activeClientConnections)
        assertTrue(metricEvents.contains(ProxyTrafficMetricsEvent.ConnectionAccepted))
        assertTrue(metricEvents.contains(ProxyTrafficMetricsEvent.ConnectionRejected))
        assertTrue(metricEvents.any { it is ProxyTrafficMetricsEvent.BytesSent && it.bytes > 0 })
        assertTrue(metricEvents.contains(ProxyTrafficMetricsEvent.ConnectionClosed))
    }

    @Test
    fun `header read idle timeout records partial header bytes received`() {
        val listener =
            assertIs<ProxyServerSocketBindResult.Bound>(
                ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
            ).listener
        val server =
            ProxyBoundClientConnectionHandler(
                exchangeHandler = exchangeHandler(managementHandler = ThrowingManagementHandler()),
            )
        val metricEvents = CopyOnWriteArrayList<ProxyTrafficMetricsEvent>()
        val partialHeader = "GET http://example.com/ HTTP/1.1\r\nHost: example.com"
        val serverFailure = AtomicReference<Throwable?>()

        listener.use {
            Socket(LOOPBACK_HOST, listener.listenPort).use { client ->
                client.soTimeout = CLIENT_TEST_READ_TIMEOUT_MILLIS
                val serverThread =
                    thread(start = true) {
                        serverFailure.capture {
                            server.handleNext(
                                listener = listener,
                                config = config,
                                clientHeaderReadIdleTimeoutMillis = 50,
                                recordMetricEvent = { metricEvents.add(it) },
                            )
                        }
                    }

                client.getOutputStream().write(partialHeader.toByteArray(Charsets.US_ASCII))
                client.getOutputStream().flush()
                assertEquals(
                    "HTTP/1.1 408 Request Timeout",
                    BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII)).readLine(),
                )

                serverThread.join(1_000)
                assertTrue(!serverThread.isAlive, "server handler should finish after the idle timeout response")
                serverFailure.get()?.let { throw it }
            }
        }

        assertTrue(metricEvents.contains(ProxyTrafficMetricsEvent.BytesReceived(partialHeader.length.toLong())))
        assertEquals(0, server.activeClientConnections)
    }

    private fun exchangeHandler(managementHandler: ManagementApiHandler): ProxyClientStreamExchangeHandler =
        ProxyClientStreamExchangeHandler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = ThrowingConnectConnector(),
            managementHandler = managementHandler,
        )

    private fun managementRequestBytes(): ByteArray =
        (
            "POST /api/service/stop HTTP/1.1\r\n" +
                "Host: phone.local\r\n" +
                "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    private fun httpProxyRequestBytes(): ByteArray =
        (
            "GET http://origin.example/resource HTTP/1.1\r\n" +
                "Host: origin.example\r\n" +
                "\r\n"
        ).toByteArray(Charsets.US_ASCII)

    private class BlockingManagementHandler : ManagementApiHandler {
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            started.countDown()
            assertTrue(released.await(1, TimeUnit.SECONDS), "management handler should be released")
            return ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}""")
        }

        fun awaitStarted(): Boolean = started.await(1, TimeUnit.SECONDS)

        fun release() {
            released.countDown()
        }
    }

    private class BlockingHttpResponseBodyInputStream(
        private val bodyReadStarted: CountDownLatch,
        private val releaseBody: CountDownLatch,
    ) : InputStream() {
        private val bytes =
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 4\r\n" +
                    "\r\n" +
                    "BODY"
            ).toByteArray(Charsets.US_ASCII)
        private val bodyStartIndex = bytes.size - 4
        private var index = 0
        private var released = false

        override fun read(): Int {
            awaitBodyReleaseIfNeeded()
            return if (index < bytes.size) {
                bytes[index++].toInt() and BYTE_MASK
            } else {
                END_OF_STREAM
            }
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            require(offset >= 0 && length >= 0 && length <= buffer.size - offset) {
                "Invalid buffer range"
            }
            if (length == 0) {
                return 0
            }
            awaitBodyReleaseIfNeeded()
            if (index >= bytes.size) {
                return END_OF_STREAM
            }

            val count = minOf(length, bytes.size - index)
            bytes.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }

        private fun awaitBodyReleaseIfNeeded() {
            if (!released && index >= bodyStartIndex) {
                bodyReadStarted.countDown()
                assertTrue(releaseBody.await(1, TimeUnit.SECONDS), "origin response body should be released")
                released = true
            }
        }
    }

    private class ThrowingHttpConnector : OutboundHttpOriginConnector {
        override fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult {
            error("HTTP connector must not be called")
        }
    }

    private class ThrowingConnectConnector : OutboundConnectTunnelConnector {
        override fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult {
            error("CONNECT connector must not be called")
        }
    }

    private class ThrowingManagementHandler : ManagementApiHandler {
        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            error("Management handler must not be called")
        }
    }

    private fun AtomicReference<Throwable?>.capture(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            set(throwable)
        }
    }

    private fun CopyOnWriteArrayList<ProxyTrafficMetricsEvent>.awaitEvents(expected: List<ProxyTrafficMetricsEvent>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (toList() == expected) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MANAGEMENT_TOKEN = "management-token"
        const val CLIENT_TEST_READ_TIMEOUT_MILLIS = 1_000
        const val BYTE_MASK = 0xff
        const val END_OF_STREAM = -1
    }
}
