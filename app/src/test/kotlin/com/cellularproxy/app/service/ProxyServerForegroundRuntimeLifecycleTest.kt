package com.cellularproxy.app.service

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyBoundClientConnectionHandler
import com.cellularproxy.proxy.server.ProxyBoundServerAcceptLoopResult
import com.cellularproxy.proxy.server.ProxyServerRuntime
import com.cellularproxy.proxy.server.ProxyServerRuntimeResult
import com.cellularproxy.proxy.server.ProxyServerRuntimeStartupResult
import com.cellularproxy.proxy.server.ProxyServerRuntimeStopResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyStartupError
import java.io.Closeable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyServerForegroundRuntimeLifecycleTest {
    @Test
    fun `startup failure is surfaced and no runtime is retained`() {
        var startCalls = 0
        val lifecycle =
            ProxyServerForegroundRuntimeLifecycle(
                startRuntime = {
                    startCalls += 1
                    ProxyServerRuntimeResult.StartupFailed(
                        ProxyServerRuntimeStartupResult.Failed(
                            startupError = ProxyStartupError.InvalidListenAddress,
                            status =
                                com.cellularproxy.shared.proxy.ProxyServiceStatus.failed(
                                    startupError = ProxyStartupError.InvalidListenAddress,
                                    configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                                ),
                        ),
                    )
                },
            )

        val exception =
            assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                lifecycle.startProxyRuntime()
            }

        assertEquals(ProxyStartupError.InvalidListenAddress, exception.startupError)
        assertEquals(1, startCalls)

        lifecycle.stopProxyRuntime()
        assertFalse(lifecycle.hasRunningRuntime)
    }

    @Test
    fun `duplicate start does not launch a replacement runtime`() {
        RuntimeHarness().use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()
            lifecycle.startProxyRuntime()

            assertEquals(1, harness.startCalls)
            assertTrue(lifecycle.hasRunningRuntime)

            lifecycle.stopProxyRuntime()
            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertFalse(lifecycle.hasRunningRuntime)
        }
    }

    @Test
    fun `installer runs proxy-server start synchronously but queues stop off caller thread`() {
        val stopTimedOut = CountDownLatch(1)
        RuntimeHarness(acceptLoopExecutor = NeverRunningExecutorService(stopTimedOut)).use { harness ->
            val lifecycle = harness.lifecycle(stopTimeoutMillis = 1)
            val registration = ForegroundProxyRuntimeLifecycleInstaller.install(lifecycle)

            try {
                val installed = ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle

                installed.startProxyRuntime()
                assertTrue(lifecycle.hasRunningRuntime)
                installed.stopProxyRuntime()

                assertTrue(lifecycle.hasPendingStop)
                assertTrue(lifecycle.hasRunningRuntime)
                assertTrue(stopTimedOut.await(1, TimeUnit.SECONDS))
                assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
                assertEquals(ProxyServerRuntimeStopResult.TimedOut, lifecycle.lastStopFailure)
            } finally {
                try {
                    registration.close()
                } catch (_: ProxyServerForegroundRuntimeStopException) {
                    // The fixture keeps the accept-loop future incomplete to verify non-blocking stop.
                }
            }
        }
    }

    @Test
    fun `stop requests and awaits the running proxy runtime`() {
        RuntimeHarness().use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()
            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertFalse(lifecycle.hasRunningRuntime)
            assertEquals(ProxyServiceState.Stopped, harness.lastRunningRuntime!!.status.state)
        }
    }

    @Test
    fun `stop timeout keeps retained runtime available for a later retry`() {
        RuntimeHarness(acceptLoopExecutor = NeverRunningExecutorService()).use { harness ->
            val lifecycle = harness.lifecycle(stopTimeoutMillis = 1)

            lifecycle.startProxyRuntime()

            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertEquals(ProxyServerRuntimeStopResult.TimedOut, lifecycle.lastStopFailure)
            assertTrue(lifecycle.hasRunningRuntime)
            assertEquals(1, harness.startCalls)
        }
    }

    @Test
    fun `start while stop is pending waits for stop completion before launching replacement runtime`() {
        RuntimeHarness().use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()
            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.hasPendingStop)
            lifecycle.startProxyRuntime()

            assertFalse(lifecycle.hasPendingStop)
            assertTrue(lifecycle.hasRunningRuntime)
            assertEquals(2, harness.startCalls)
        }
    }

    @Test
    fun `start while stop remains pending is rejected after bounded wait`() {
        RuntimeHarness(acceptLoopExecutor = NeverRunningExecutorService()).use { harness ->
            val lifecycle = harness.lifecycle(stopTimeoutMillis = 1)

            lifecycle.startProxyRuntime()
            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.hasPendingStop)
            assertFailsWith<IllegalStateException> {
                lifecycle.startProxyRuntime()
            }
            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertEquals(1, harness.startCalls)
        }
    }

    @Test
    fun `stop without a running runtime is a no-op`() {
        val lifecycle =
            ProxyServerForegroundRuntimeLifecycle(
                startRuntime = { error("stop must not start the runtime") },
            )

        lifecycle.stopProxyRuntime()

        assertFalse(lifecycle.hasRunningRuntime)
    }

    @Test
    fun `close stops the retained runtime once`() {
        RuntimeHarness().use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()
            lifecycle.close()
            lifecycle.close()

            assertFalse(lifecycle.hasRunningRuntime)
            assertEquals(ProxyServiceState.Stopped, harness.lastRunningRuntime!!.status.state)
        }
    }

    @Test
    fun `close timeout marks lifecycle closed and releases retained runtime ownership`() {
        RuntimeHarness(acceptLoopExecutor = NeverRunningExecutorService()).use { harness ->
            val lifecycle = harness.lifecycle(stopTimeoutMillis = 1)

            lifecycle.startProxyRuntime()

            assertFailsWith<ProxyServerForegroundRuntimeStopException> {
                lifecycle.close()
            }

            assertFalse(lifecycle.hasRunningRuntime)
            assertFailsWith<IllegalStateException> {
                lifecycle.startProxyRuntime()
            }
        }
    }

    @Test
    fun `terminal accept-loop failure during stop unregisters retained runtime`() {
        RuntimeHarness(acceptLoopExecutor = FailedAcceptLoopExecutorService()).use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()

            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            val finished = assertIs<ProxyServerRuntimeStopResult.Finished>(lifecycle.lastStopFailure)
            assertIs<ProxyBoundServerAcceptLoopResult.Failed>(finished.result)
            assertFalse(lifecycle.hasRunningRuntime)
        }
    }

    @Test
    fun `unexpected stop worker failure clears pending state without uncaught exception`() {
        RuntimeHarness(acceptLoopExecutor = ErrorAcceptLoopExecutorService()).use { harness ->
            val lifecycle = harness.lifecycle()

            lifecycle.startProxyRuntime()
            lifecycle.stopProxyRuntime()

            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertFalse(lifecycle.hasPendingStop)
            assertTrue(lifecycle.hasRunningRuntime)
            assertIs<ProxyServerForegroundRuntimeStopWorkerException>(lifecycle.lastStopWorkerFailure)
            assertEquals(1, harness.startCalls)
        }
    }

    @Test
    fun `installer preserves synchronous startup failure for proxy-server lifecycle`() {
        val lifecycle =
            ProxyServerForegroundRuntimeLifecycle(
                startRuntime = {
                    ProxyServerRuntimeResult.StartupFailed(
                        ProxyServerRuntimeStartupResult.Failed(
                            startupError = ProxyStartupError.MissingManagementApiToken,
                            status =
                                com.cellularproxy.shared.proxy.ProxyServiceStatus.failed(
                                    startupError = ProxyStartupError.MissingManagementApiToken,
                                    configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                                ),
                        ),
                    )
                },
            )
        val registration = ForegroundProxyRuntimeLifecycleInstaller.install(lifecycle)

        try {
            val installed = ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle

            val failure =
                assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                    installed.startProxyRuntime()
                }
            assertEquals(ProxyStartupError.MissingManagementApiToken, failure.startupError)
        } finally {
            registration.close()
        }
    }

    @Test
    fun `runtime-started hook failure requests bounded runtime cleanup before surfacing failure`() {
        RuntimeHarness(acceptLoopExecutor = NeverRunningExecutorService()).use { harness ->
            val lifecycle =
                harness.lifecycle(
                    stopTimeoutMillis = 1,
                    onRuntimeStarted = {
                        throw IllegalStateException("management handler install failed")
                    },
                )

            val failure =
                assertFailsWith<IllegalStateException> {
                    lifecycle.startProxyRuntime()
                }

            assertEquals("management handler install failed", failure.message)
            val stopFailure =
                assertIs<ProxyServerForegroundRuntimeStopException>(
                    failure.suppressed.single(),
                )
            assertEquals(ProxyServerRuntimeStopResult.TimedOut, stopFailure.stopResult)
            assertFalse(lifecycle.hasRunningRuntime)
            assertEquals(ProxyServiceState.Stopping, harness.lastRunningRuntime!!.status.state)
        }
    }
}

private class RuntimeHarness(
    private val acceptLoopExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val workerExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)

    var startCalls: Int = 0
        private set

    var lastRunningRuntime: com.cellularproxy.proxy.server.RunningProxyServerRuntime? = null
        private set

    fun lifecycle(
        stopTimeoutMillis: Long = 1_000,
        onRuntimeStarted: (com.cellularproxy.proxy.server.RunningProxyServerRuntime) -> Closeable? = { null },
    ): ProxyServerForegroundRuntimeLifecycle = ProxyServerForegroundRuntimeLifecycle(
        startRuntime = {
            startCalls += 1
            ProxyServerRuntime
                .start(
                    config =
                        AppConfig.default().copy(
                            proxy =
                                AppConfig.default().proxy.copy(
                                    listenHost = LOOPBACK_HOST,
                                    listenPort = 65_535,
                                ),
                        ),
                    managementApiTokenPresent = true,
                    observedNetworks = listOf(wifiRoute()),
                    ingressConfig = ingressConfig(),
                    connectionHandler = connectionHandler(),
                    workerExecutor = workerExecutor,
                    queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                    acceptLoopExecutor = acceptLoopExecutor,
                    recordMetricEvent = { _: ProxyTrafficMetricsEvent -> },
                    bindListener = { listenHost, _, backlog ->
                        ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
                    },
                ).also { result ->
                    if (result is ProxyServerRuntimeResult.Running) {
                        lastRunningRuntime = result.runtime
                    }
                }
        },
        onRuntimeStarted = onRuntimeStarted,
        stopTimeoutMillis = stopTimeoutMillis,
    )

    override fun close() {
        lastRunningRuntime?.stop()
        acceptLoopExecutor.shutdownNow()
        workerExecutor.shutdownNow()
        queuedClientTimeoutExecutor.shutdownNow()
        assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
    }
}

private class NeverRunningExecutorService(
    private val timeoutLatch: CountDownLatch? = null,
) : AbstractExecutorService() {
    private var shutdown = false

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = shutdown

    override fun execute(command: Runnable) = Unit

    override fun <T : Any?> newTaskFor(
        runnable: Runnable,
        value: T,
    ): RunnableFuture<T> = NeverCompletingFuture(timeoutLatch)

    override fun <T : Any?> newTaskFor(callable: Callable<T>): RunnableFuture<T> = NeverCompletingFuture(timeoutLatch)
}

private class NeverCompletingFuture<T>(
    private val timeoutLatch: CountDownLatch? = null,
) : RunnableFuture<T> {
    private var cancelled = false

    override fun run() = Unit

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled

    override fun isDone(): Boolean = cancelled

    override fun get(): T {
        while (!cancelled) {
            Thread.sleep(10)
        }
        throw CancellationException()
    }

    override fun get(
        timeout: Long,
        unit: TimeUnit,
    ): T {
        Thread.sleep(unit.toMillis(timeout))
        timeoutLatch?.countDown()
        throw TimeoutException()
    }
}

private class FailedAcceptLoopExecutorService : AbstractExecutorService() {
    private var shutdown = false

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = shutdown

    override fun execute(command: Runnable) = command.run()

    override fun <T : Any?> newTaskFor(
        runnable: Runnable,
        value: T,
    ): RunnableFuture<T> = CompletedFailedAcceptLoopFuture()

    override fun <T : Any?> newTaskFor(callable: Callable<T>): RunnableFuture<T> = CompletedFailedAcceptLoopFuture()
}

private class CompletedFailedAcceptLoopFuture<T> : RunnableFuture<T> {
    override fun run() = Unit

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = true

    override fun get(): T {
        @Suppress("UNCHECKED_CAST")
        return ProxyBoundServerAcceptLoopResult.Failed(
            acceptedClientConnections = 0,
            failure = IllegalStateException("accept loop failed"),
        ) as T
    }

    override fun get(
        timeout: Long,
        unit: TimeUnit,
    ): T = get()
}

private class ErrorAcceptLoopExecutorService : AbstractExecutorService() {
    private var shutdown = false

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = shutdown

    override fun execute(command: Runnable) = command.run()

    override fun <T : Any?> newTaskFor(
        runnable: Runnable,
        value: T,
    ): RunnableFuture<T> = ErrorAcceptLoopFuture()

    override fun <T : Any?> newTaskFor(callable: Callable<T>): RunnableFuture<T> = ErrorAcceptLoopFuture()
}

private class ErrorAcceptLoopFuture<T> : RunnableFuture<T> {
    override fun run() = Unit

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = true

    override fun get(): T = throw AssertionError("accept loop crashed")

    override fun get(
        timeout: Long,
        unit: TimeUnit,
    ): T = get()
}

private fun ingressConfig(): ProxyIngressPreflightConfig = ProxyIngressPreflightConfig(
    connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 10),
    requestAdmission =
        ProxyRequestAdmissionConfig(
            proxyAuthentication =
                ProxyAuthenticationConfig(
                    authEnabled = true,
                    credential =
                        ProxyCredential(
                            username = "proxy-user",
                            password = "proxy-password",
                        ),
                ),
            managementApiToken = "management-token",
        ),
)

private fun connectionHandler(): ProxyBoundClientConnectionHandler = ProxyBoundClientConnectionHandler(
    exchangeHandler =
        com.cellularproxy.proxy.server.ProxyClientStreamExchangeHandler(
            httpConnector = {
                OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.OutboundConnectionFailed)
            },
            connectConnector = {
                OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.OutboundConnectionFailed)
            },
            managementHandler = {
                ManagementApiResponse.json(statusCode = 200, body = "{}")
            },
        ),
)

private fun wifiRoute(): NetworkDescriptor = NetworkDescriptor(
    id = "wifi",
    category = NetworkCategory.WiFi,
    displayName = "Home Wi-Fi",
    isAvailable = true,
)

private const val LOOPBACK_HOST = "127.0.0.1"
