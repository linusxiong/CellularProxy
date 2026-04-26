package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionDisposition
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyStartupError
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyServerRuntimeTest {
    @Test
    fun `startup failure returns failed result without launching accept loop`() {
        var bindCalled = false
        val acceptLoopExecutor = RecordingExecutorService()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val invalidConfig = AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(listenHost = "localhost"),
        )

        try {
            val failed = assertIs<ProxyServerRuntimeResult.StartupFailed>(
                ProxyServerRuntime.start(
                    config = invalidConfig,
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    ingressConfig = ingressConfig(),
                    connectionHandler = connectionHandler(),
                    workerExecutor = RecordingExecutorService(),
                    queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                    acceptLoopExecutor = acceptLoopExecutor,
                    bindListener = { _, _, _ ->
                        bindCalled = true
                        error("startup preflight failure must not bind")
                    },
                ),
            )

            assertFalse(bindCalled)
            assertEquals(0, acceptLoopExecutor.executedTasks)
            assertEquals(ProxyStartupError.InvalidListenAddress, failed.startup.startupError)
            assertEquals(ProxyServiceState.Failed, failed.startup.status.state)
        } finally {
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `successful startup launches accept loop and stop handle closes listener`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)

        val running = assertIs<ProxyServerRuntimeResult.Running>(
            ProxyServerRuntime.start(
                config = AppConfig.default().copy(
                    proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                ),
                managementApiTokenPresent = true,
                observedNetworks = listOf(wifiRoute()),
                ingressConfig = ingressConfig(),
                connectionHandler = connectionHandler(),
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
            ),
        ).runtime

        try {
            assertEquals(ProxyServiceState.Running, running.status.state)
            assertEquals(LOOPBACK_HOST, running.status.listenHost)
            assertEquals(listener.listenPort, running.status.listenPort)
            assertFalse(running.listener.isClosed)

            running.stop()

            val stopped = assertIs<ProxyServerRuntimeStopResult.Finished>(
                running.awaitStopped(timeoutMillis = 1_000),
            )
            assertEquals(
                0,
                assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(stopped.result).acceptedClientConnections,
            )
            assertTrue(running.listener.isClosed)
        } finally {
            running.stop()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `runtime stop request applies service stop transition and records stopped status`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)

        val running = assertIs<ProxyServerRuntimeResult.Running>(
            ProxyServerRuntime.start(
                config = AppConfig.default().copy(
                    proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                ),
                managementApiTokenPresent = true,
                observedNetworks = listOf(wifiRoute()),
                ingressConfig = ingressConfig(),
                connectionHandler = connectionHandler(),
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
            ),
        ).runtime

        try {
            val acceptedStop = running.requestStop()

            assertEquals(ProxyServiceStopTransitionDisposition.Accepted, acceptedStop.disposition)
            assertEquals(ProxyServiceState.Stopping, acceptedStop.status.state)
            assertEquals(ProxyServiceState.Stopping, running.status.state)
            assertTrue(running.listener.isClosed)

            val duplicateStop = running.requestStop()
            assertEquals(ProxyServiceStopTransitionDisposition.Duplicate, duplicateStop.disposition)
            assertEquals(ProxyServiceState.Stopping, duplicateStop.status.state)

            val stopped = assertIs<ProxyServerRuntimeStopResult.Finished>(
                running.awaitStopped(timeoutMillis = 1_000),
            )
            assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(stopped.result)
            assertEquals(ProxyServiceState.Stopped, running.status.state)
        } finally {
            running.stop()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `accept loop launch rejection closes bound listener`() {
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)

        try {
            val failed = assertIs<ProxyServerRuntimeResult.AcceptLoopLaunchFailed>(
                ProxyServerRuntime.start(
                    config = AppConfig.default().copy(
                        proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                    ),
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    ingressConfig = ingressConfig(),
                    connectionHandler = connectionHandler(),
                    workerExecutor = RecordingExecutorService(),
                    queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                    acceptLoopExecutor = RejectingExecutorService(),
                    bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
                ),
            )

            assertTrue(listener.isClosed)
            assertEquals("rejected", failed.exception.message)
        } finally {
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalid runtime tunables fail before binding listener`() {
        var bindCalled = false
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)

        try {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                ProxyServerRuntime.start(
                    config = AppConfig.default().copy(
                        proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                    ),
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    ingressConfig = ingressConfig(),
                    connectionHandler = connectionHandler(),
                    workerExecutor = RecordingExecutorService(),
                    queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                    acceptLoopExecutor = RecordingExecutorService(),
                    httpBufferSize = 0,
                    bindListener = { _, _, _ ->
                        bindCalled = true
                        error("invalid runtime tunables must not bind")
                    },
                )
            }

            assertFalse(bindCalled)
        } finally {
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `post launch accept loop failure closes listener and is observable through typed await`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)

        val running = assertIs<ProxyServerRuntimeResult.Running>(
            ProxyServerRuntime.start(
                config = AppConfig.default().copy(
                    proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                ),
                managementApiTokenPresent = true,
                observedNetworks = listOf(wifiRoute()),
                ingressConfig = ingressConfig(),
                connectionHandler = connectionHandler(),
                workerExecutor = RejectingExecutorService(),
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
            ),
        ).runtime

        try {
            Socket(LOOPBACK_HOST, listener.listenPort).use {
                val stopped = assertIs<ProxyServerRuntimeStopResult.Finished>(
                    running.awaitStopped(timeoutMillis = 1_000),
                )
                assertIs<ProxyBoundServerAcceptLoopResult.Failed>(stopped.result)
            }

            assertTrue(listener.isClosed)
        } finally {
            running.stop()
            acceptLoopExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private fun ingressConfig(): ProxyIngressPreflightConfig =
        ProxyIngressPreflightConfig(
            connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 1),
            requestAdmission = ProxyRequestAdmissionConfig(
                proxyAuthentication = ProxyAuthenticationConfig(
                    authEnabled = false,
                    credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                ),
                managementApiToken = "management-token",
            ),
        )

    private fun connectionHandler(): ProxyBoundClientConnectionHandler =
        ProxyBoundClientConnectionHandler(
            exchangeHandler = ProxyClientStreamExchangeHandler(
                httpConnector = {
                    OutboundHttpOriginOpenResult.Failed(
                        OutboundHttpOriginOpenFailure.SelectedRouteUnavailable,
                    )
                },
                connectConnector = {
                    OutboundConnectTunnelOpenResult.Failed(
                        OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable,
                    )
                },
                managementHandler = object : ManagementApiHandler {
                    override fun handle(operation: ManagementApiOperation): ManagementApiResponse =
                        ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
            ),
        )

    private fun wifiRoute(): NetworkDescriptor =
        NetworkDescriptor(
            id = "wifi",
            category = NetworkCategory.WiFi,
            displayName = "Home Wi-Fi",
            isAvailable = true,
        )
}

private class RecordingExecutorService : AbstractExecutorService() {
    var executedTasks: Int = 0
        private set
    private var shutdown = false

    override fun execute(command: Runnable) {
        executedTasks += 1
        command.run()
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): List<Runnable> {
        shutdown = true
        return emptyList()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

private class RejectingExecutorService : AbstractExecutorService() {
    override fun execute(command: Runnable) {
        throw RejectedExecutionException("rejected")
    }

    override fun shutdown() = Unit

    override fun shutdownNow(): List<Runnable> = Collections.emptyList()

    override fun isShutdown(): Boolean = false

    override fun isTerminated(): Boolean = false

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

private const val LOOPBACK_HOST = "127.0.0.1"
