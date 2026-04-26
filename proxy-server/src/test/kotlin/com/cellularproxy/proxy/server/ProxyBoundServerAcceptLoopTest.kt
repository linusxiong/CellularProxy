package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyBoundServerAcceptLoopTest {
    private val config = ProxyIngressPreflightConfig(
        connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 1),
        requestAdmission = ProxyRequestAdmissionConfig(
            proxyAuthentication = ProxyAuthenticationConfig(
                authEnabled = false,
                credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
            ),
            managementApiToken = MANAGEMENT_TOKEN,
        ),
    )

    @Test
    fun `accept loop dispatches clients concurrently and stops when listener is closed`() {
        val listener = assertIs<ProxyServerSocketBindResult.Bound>(
            ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
        ).listener
        val blockingManagementHandler = BlockingManagementHandler()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val connectionHandler = ProxyBoundClientConnectionHandler(
            exchangeHandler = exchangeHandler(managementHandler = blockingManagementHandler),
        )
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedTimeoutExecutor,
        )
        val loopResult = AtomicReference<ProxyBoundServerAcceptLoopResult?>()
        val loopFailure = AtomicReference<Throwable?>()

        val loopThread = thread(start = true) {
            loopFailure.capture {
                loopResult.set(
                    acceptLoop.run(
                        listener = listener,
                        config = config,
                    ),
                )
            }
        }

        try {
            val firstClientFailure = AtomicReference<Throwable?>()
            val firstClient = Socket(LOOPBACK_HOST, listener.listenPort)
            val firstClientThread = thread(start = true) {
                firstClientFailure.capture {
                    firstClient.use { socket ->
                        socket.getOutputStream().write(managementRequestBytes())
                        socket.getOutputStream().flush()
                        assertEquals(
                            "HTTP/1.1 202 Accepted",
                            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                        )
                    }
                }
            }

            assertTrue(blockingManagementHandler.awaitStarted(), "first request should reach the worker handler")
            assertTrue(
                connectionHandler.awaitActiveClientConnections(1),
                "first accepted connection should be active",
            )

            Socket(LOOPBACK_HOST, listener.listenPort).use { secondClient ->
                secondClient.soTimeout = CLIENT_TEST_READ_TIMEOUT_MILLIS
                secondClient.getOutputStream().write(managementRequestBytes())
                secondClient.getOutputStream().flush()

                assertEquals(
                    "HTTP/1.1 503 Service Unavailable",
                    BufferedReader(InputStreamReader(secondClient.getInputStream(), Charsets.US_ASCII)).readLine(),
                )
            }

            assertTrue(
                connectionHandler.awaitActiveClientConnections(1),
                "second rejected connection should release while first remains active",
            )
            acceptLoop.stop(listener)
            blockingManagementHandler.release()

            loopThread.join(1_000)
            firstClientThread.join(1_000)
            assertTrue(!loopThread.isAlive, "accept loop should exit after stop closes the listener")
            assertTrue(!firstClientThread.isAlive, "first client should finish after handler release")
            loopFailure.get()?.let { throw it }
            firstClientFailure.get()?.let { throw it }

            val stopped = assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(loopResult.get())
            assertEquals(2, stopped.acceptedClientConnections)
            assertTrue(
                connectionHandler.awaitActiveClientConnections(0),
                "all worker handlers should release active connection state",
            )
        } finally {
            acceptLoop.stop(listener)
            blockingManagementHandler.release()
            workerExecutor.shutdownNow()
            queuedTimeoutExecutor.shutdownNow()
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS), "worker executor should stop")
            assertTrue(queuedTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS), "timeout executor should stop")
        }
    }

    @Test
    fun `accepted sockets are reserved before queued worker execution`() {
        val listener = assertIs<ProxyServerSocketBindResult.Bound>(
            ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
        ).listener
        val blockingManagementHandler = BlockingManagementHandler()
        val workerExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        )
        val queuedTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val connectionHandler = ProxyBoundClientConnectionHandler(
            exchangeHandler = exchangeHandler(managementHandler = blockingManagementHandler),
        )
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedTimeoutExecutor,
        )
        val loopResult = AtomicReference<ProxyBoundServerAcceptLoopResult?>()
        val loopFailure = AtomicReference<Throwable?>()

        val loopThread = thread(start = true) {
            loopFailure.capture {
                loopResult.set(acceptLoop.run(listener = listener, config = config))
            }
        }

        try {
            val firstClient = Socket(LOOPBACK_HOST, listener.listenPort)
            val firstClientFailure = AtomicReference<Throwable?>()
            val firstClientThread = thread(start = true) {
                firstClientFailure.capture {
                    firstClient.use { socket ->
                        socket.getOutputStream().write(managementRequestBytes())
                        socket.getOutputStream().flush()
                        assertEquals(
                            "HTTP/1.1 202 Accepted",
                            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                        )
                    }
                }
            }

            assertTrue(blockingManagementHandler.awaitStarted(), "first request should occupy the only worker")

            val secondClient = Socket(LOOPBACK_HOST, listener.listenPort)
            secondClient.soTimeout = CLIENT_TEST_READ_TIMEOUT_MILLIS
            secondClient.getOutputStream().write(managementRequestBytes())
            secondClient.getOutputStream().flush()
            assertTrue(
                connectionHandler.awaitActiveClientConnections(2),
                "queued accepted socket should count as active before its worker runs",
            )

            blockingManagementHandler.release()
            assertEquals(
                "HTTP/1.1 503 Service Unavailable",
                BufferedReader(InputStreamReader(secondClient.getInputStream(), Charsets.US_ASCII)).readLine(),
            )
            secondClient.close()

            acceptLoop.stop(listener)
            loopThread.join(1_000)
            firstClientThread.join(1_000)
            assertTrue(!loopThread.isAlive, "accept loop should exit after stop")
            assertTrue(!firstClientThread.isAlive, "first client should finish after release")
            loopFailure.get()?.let { throw it }
            firstClientFailure.get()?.let { throw it }
            assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(loopResult.get())
            assertTrue(connectionHandler.awaitActiveClientConnections(0), "all reservations should be released")
        } finally {
            acceptLoop.stop(listener)
            blockingManagementHandler.release()
            workerExecutor.shutdownNow()
            queuedTimeoutExecutor.shutdownNow()
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS), "worker executor should stop")
            assertTrue(queuedTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS), "timeout executor should stop")
        }
    }

    @Test
    fun `queued accepted socket times out before worker starts reading headers`() {
        val listener = assertIs<ProxyServerSocketBindResult.Bound>(
            ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
        ).listener
        val blockingManagementHandler = BlockingManagementHandler()
        val workerExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        )
        val queuedTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val connectionHandler = ProxyBoundClientConnectionHandler(
            exchangeHandler = exchangeHandler(managementHandler = blockingManagementHandler),
        )
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedTimeoutExecutor,
        )
        val loopResult = AtomicReference<ProxyBoundServerAcceptLoopResult?>()
        val loopFailure = AtomicReference<Throwable?>()

        val loopThread = thread(start = true) {
            loopFailure.capture {
                loopResult.set(
                    acceptLoop.run(
                        listener = listener,
                        config = config,
                        clientHeaderReadIdleTimeoutMillis = 50,
                    ),
                )
            }
        }

        try {
            val firstClient = Socket(LOOPBACK_HOST, listener.listenPort)
            val firstClientFailure = AtomicReference<Throwable?>()
            val firstClientThread = thread(start = true) {
                firstClientFailure.capture {
                    firstClient.use { socket ->
                        socket.getOutputStream().write(managementRequestBytes())
                        socket.getOutputStream().flush()
                        assertEquals(
                            "HTTP/1.1 202 Accepted",
                            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                        )
                    }
                }
            }

            assertTrue(blockingManagementHandler.awaitStarted(), "first request should occupy the only worker")

            Socket(LOOPBACK_HOST, listener.listenPort).use { queuedClient ->
                queuedClient.soTimeout = CLIENT_TEST_READ_TIMEOUT_MILLIS
                assertEquals(
                    "HTTP/1.1 408 Request Timeout",
                    BufferedReader(InputStreamReader(queuedClient.getInputStream(), Charsets.US_ASCII)).readLine(),
                )
            }
            assertTrue(
                connectionHandler.awaitActiveClientConnections(1),
                "queued timeout should release its active reservation before the worker starts",
            )

            blockingManagementHandler.release()
            acceptLoop.stop(listener)
            loopThread.join(1_000)
            firstClientThread.join(1_000)
            assertTrue(!loopThread.isAlive, "accept loop should exit after stop")
            assertTrue(!firstClientThread.isAlive, "first client should finish after release")
            loopFailure.get()?.let { throw it }
            firstClientFailure.get()?.let { throw it }
            assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(loopResult.get())
            assertTrue(connectionHandler.awaitActiveClientConnections(0), "all reservations should be released")
        } finally {
            acceptLoop.stop(listener)
            blockingManagementHandler.release()
            workerExecutor.shutdownNow()
            queuedTimeoutExecutor.shutdownNow()
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS), "worker executor should stop")
            assertTrue(queuedTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS), "timeout executor should stop")
        }
    }

    @Test
    fun `stop unblocks accept loop before any client connects`() {
        val listener = assertIs<ProxyServerSocketBindResult.Bound>(
            ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
        ).listener
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val connectionHandler = ProxyBoundClientConnectionHandler(
            exchangeHandler = exchangeHandler(managementHandler = ThrowingManagementHandler()),
        )
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedTimeoutExecutor,
        )
        val loopResult = AtomicReference<ProxyBoundServerAcceptLoopResult?>()
        val loopFailure = AtomicReference<Throwable?>()

        val loopThread = thread(start = true) {
            loopFailure.capture {
                loopResult.set(acceptLoop.run(listener = listener, config = config))
            }
        }

        try {
            Thread.sleep(50)
            acceptLoop.stop(listener)

            loopThread.join(1_000)
            assertTrue(!loopThread.isAlive, "accept loop should exit after stop")
            loopFailure.get()?.let { throw it }
            val stopped = assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(loopResult.get())
            assertEquals(0, stopped.acceptedClientConnections)
            assertEquals(0, connectionHandler.activeClientConnections)
        } finally {
            acceptLoop.stop(listener)
            workerExecutor.shutdownNow()
            queuedTimeoutExecutor.shutdownNow()
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS), "worker executor should stop")
            assertTrue(queuedTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS), "timeout executor should stop")
        }
    }

    @Test
    fun `non-stop accept setup failure returns failed result`() {
        val listener = BoundProxyServerSocket(
            serverSocket = ThrowingAcceptServerSocket(),
            listenHost = LOOPBACK_HOST,
        )
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val connectionHandler = ProxyBoundClientConnectionHandler(
            exchangeHandler = exchangeHandler(managementHandler = ThrowingManagementHandler()),
        )
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedTimeoutExecutor,
        )

        try {
            val failed = assertIs<ProxyBoundServerAcceptLoopResult.Failed>(
                acceptLoop.run(listener = listener, config = config),
            )

            assertEquals(0, failed.acceptedClientConnections)
            assertIs<IOException>(failed.failure)
            assertEquals(0, connectionHandler.activeClientConnections)
        } finally {
            acceptLoop.stop(listener)
            workerExecutor.shutdownNow()
            queuedTimeoutExecutor.shutdownNow()
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS), "worker executor should stop")
            assertTrue(queuedTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS), "timeout executor should stop")
        }
    }

    private fun exchangeHandler(
        managementHandler: ManagementApiHandler,
    ): ProxyClientStreamExchangeHandler =
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

    private class ThrowingAcceptServerSocket : ServerSocket() {
        override fun accept(): Socket {
            throw IOException("accept setup failed")
        }
    }

    private fun AtomicReference<Throwable?>.capture(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            set(throwable)
        }
    }

    private fun ProxyBoundClientConnectionHandler.awaitActiveClientConnections(expected: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (activeClientConnections == expected) {
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
    }
}
