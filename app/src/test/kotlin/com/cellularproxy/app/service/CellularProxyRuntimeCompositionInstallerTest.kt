package com.cellularproxy.app.service

import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandAuditRecord
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CellularProxyRuntimeCompositionInstallerTest {
    @AfterTest
    fun resetRegistry() {
        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
    }

    @Test
    fun `ready bootstrap installs runtime lifecycle and owns route monitor and executors`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors = RuntimeCompositionExecutorResources(
            workerExecutor = Executors.newSingleThreadExecutor(),
            queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
            acceptLoopExecutor = Executors.newSingleThreadExecutor(),
        )
        val installation = CellularProxyRuntimeCompositionInstaller.installForTesting(
            bootstrapResult = readyBootstrap(),
            observedNetworks = routeMonitor::observedNetworks,
            routeMonitor = routeMonitor,
            socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
            executorResources = executors,
        )

        assertIs<ProxyServerForegroundRuntimeInstallResult.Installed>(installation.installResult)
        assertNotSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )

        installation.close()
        installation.close()

        assertTrue(routeMonitor.closed)
        assertTrue(executors.workerExecutor.isShutdown)
        assertTrue(executors.queuedClientTimeoutExecutor.isShutdown)
        assertTrue(executors.acceptLoopExecutor.isShutdown)
        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
        assertTerminates(executors.workerExecutor)
        assertTerminates(executors.queuedClientTimeoutExecutor)
        assertTerminates(executors.acceptLoopExecutor)
    }

    @Test
    fun `invalid sensitive bootstrap closes owned resources without installing runtime lifecycle`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors = RuntimeCompositionExecutorResources(
            workerExecutor = Executors.newSingleThreadExecutor(),
            queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
            acceptLoopExecutor = Executors.newSingleThreadExecutor(),
        )

        val installation = CellularProxyRuntimeCompositionInstaller.installForTesting(
            bootstrapResult = AppConfigBootstrapResult.InvalidSensitiveConfig(
                SensitiveConfigInvalidReason.UndecryptableSecret,
            ),
            observedNetworks = routeMonitor::observedNetworks,
            routeMonitor = routeMonitor,
            socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
            executorResources = executors,
        )

        assertEquals(
            ProxyServerForegroundRuntimeInstallResult.InvalidSensitiveConfig(
                SensitiveConfigInvalidReason.UndecryptableSecret,
            ),
            installation.installResult,
        )
        assertTrue(routeMonitor.closed)
        assertTrue(executors.workerExecutor.isShutdown)
        assertTrue(executors.queuedClientTimeoutExecutor.isShutdown)
        assertTrue(executors.acceptLoopExecutor.isShutdown)
        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )

        installation.close()
        assertTerminates(executors.workerExecutor)
        assertTerminates(executors.queuedClientTimeoutExecutor)
        assertTerminates(executors.acceptLoopExecutor)
    }

    @Test
    fun `installer failures close owned route monitor and executors before propagating`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors = RuntimeCompositionExecutorResources(
            workerExecutor = Executors.newSingleThreadExecutor(),
            queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
            acceptLoopExecutor = Executors.newSingleThreadExecutor(),
        )

        assertFailsWith<IllegalArgumentException> {
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                outboundConnectTimeoutMillis = 0L,
            )
        }

        assertTrue(routeMonitor.closed)
        assertTrue(executors.workerExecutor.isShutdown)
        assertTrue(executors.queuedClientTimeoutExecutor.isShutdown)
        assertTrue(executors.acceptLoopExecutor.isShutdown)
        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
        assertTerminates(executors.workerExecutor)
        assertTerminates(executors.queuedClientTimeoutExecutor)
        assertTerminates(executors.acceptLoopExecutor)
    }

    @Test
    fun `production root availability provider emits root command audit records when root operations are enabled`() {
        val auditRecords = mutableListOf<RootCommandAuditRecord>()
        val provider = createRootAvailabilityProvider(
            rootOperationsEnabled = { true },
            processExecutor = { _, _ ->
                com.cellularproxy.root.RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "0",
                    stderr = "",
                )
            },
            recordRootAudit = auditRecords::add,
        )

        assertEquals(com.cellularproxy.shared.root.RootAvailabilityStatus.Available, provider())
        assertEquals(
            listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed),
            auditRecords.map { it.phase },
        )
    }

    @Test
    fun `production root audit sink failure does not break root availability and reports failure`() {
        val failures = mutableListOf<Throwable>()
        val provider = createRootAvailabilityProvider(
            rootOperationsEnabled = { true },
            processExecutor = { _, _ ->
                com.cellularproxy.root.RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "0",
                    stderr = "",
                )
            },
            recordRootAudit = nonFatalRootAuditRecorder(
                recordRootAudit = { throw java.io.IOException("audit disk full") },
                reportRootAuditFailure = failures::add,
            ),
        )

        assertEquals(com.cellularproxy.shared.root.RootAvailabilityStatus.Available, provider())
        assertEquals(2, failures.size)
        assertTrue(failures.all { it is java.io.IOException })
    }

    private fun readyBootstrap(): AppConfigBootstrapResult.Ready =
        AppConfigBootstrapResult.Ready(
            plainConfig = AppConfig.default().copy(
                proxy = AppConfig.default().proxy.copy(
                    listenHost = "127.0.0.1",
                    listenPort = 8080,
                ),
            ),
            sensitiveConfig = SensitiveConfig(
                proxyCredential = ProxyCredential(
                    username = "proxy-user",
                    password = "proxy-password",
                ),
                managementApiToken = "management-token",
            ),
            createdDefaultSecrets = false,
            reconciledPlainConfig = false,
        )

    private fun wifiRoute(): NetworkDescriptor =
        NetworkDescriptor(
            id = "wifi",
            category = NetworkCategory.WiFi,
            displayName = "Home Wi-Fi",
            isAvailable = true,
        )
}

private class RecordingRouteMonitor(
    private val networks: List<NetworkDescriptor>,
) : Closeable {
    var closed: Boolean = false
        private set

    fun observedNetworks(): List<NetworkDescriptor> = networks

    override fun close() {
        closed = true
    }
}

private object CompositionUnavailableBoundNetworkSocketConnector : BoundNetworkSocketConnector {
    override suspend fun connect(
        network: NetworkDescriptor,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult =
        BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
}

private fun assertTerminates(executor: ExecutorService) {
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
}

private fun assertTerminates(executor: ScheduledExecutorService) {
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
}
