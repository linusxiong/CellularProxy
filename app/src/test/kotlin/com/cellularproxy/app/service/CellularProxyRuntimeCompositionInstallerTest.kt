package com.cellularproxy.app.service

import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
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
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
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
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )

        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult =
                    AppConfigBootstrapResult.InvalidSensitiveConfig(
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
        val executors =
            RuntimeCompositionExecutorResources(
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
        val provider =
            createRootAvailabilityProvider(
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
        val provider =
            createRootAvailabilityProvider(
                rootOperationsEnabled = { true },
                processExecutor = { _, _ ->
                    com.cellularproxy.root.RootCommandProcessResult.Completed(
                        exitCode = 0,
                        stdout = "0",
                        stderr = "",
                    )
                },
                recordRootAudit =
                    nonFatalRootAuditRecorder(
                        recordRootAudit = { throw java.io.IOException("audit disk full") },
                        reportRootAuditFailure = failures::add,
                    ),
            )

        assertEquals(com.cellularproxy.shared.root.RootAvailabilityStatus.Available, provider())
        assertEquals(2, failures.size)
        assertTrue(failures.all { it is java.io.IOException })
    }

    @Test
    fun `default production rotation callbacks reject when execution is not wired`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                rootOperationsEnabled = { true },
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
                },
            )

        try {
            val installed =
                assertIs<ProxyServerForegroundRuntimeInstallResult.Installed>(
                    installation.installResult,
                )

            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle.startProxyRuntime()

            val mobileData = installed.managementHandlerReference.handle(ManagementApiOperation.RotateMobileData)
            val airplaneMode = installed.managementHandlerReference.handle(ManagementApiOperation.RotateAirplaneMode)

            assertEquals(409, mobileData.statusCode)
            assertContains(mobileData.body, """"disposition":"rejected"""")
            assertContains(mobileData.body, """"state":"failed"""")
            assertContains(mobileData.body, """"operation":"mobile_data"""")
            assertContains(mobileData.body, """"failureReason":"execution_unavailable"""")

            assertEquals(409, airplaneMode.statusCode)
            assertContains(airplaneMode.body, """"disposition":"rejected"""")
            assertContains(airplaneMode.body, """"state":"failed"""")
            assertContains(airplaneMode.body, """"operation":"airplane_mode"""")
            assertContains(airplaneMode.body, """"failureReason":"execution_unavailable"""")
        } finally {
            installation.close()
            assertTerminates(executors.workerExecutor)
            assertTerminates(executors.queuedClientTimeoutExecutor)
            assertTerminates(executors.acceptLoopExecutor)
        }
    }

    @Test
    fun `runtime rotation handler factory is installed through composition boundary`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val runtimeRotationHandler = CompositionRecordingRuntimeRotationRequestHandler()
        var handlerFactoryCalls = 0
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                rootOperationsEnabled = { true },
                runtimeRotationRequestHandlerFactory = {
                    handlerFactoryCalls += 1
                    runtimeRotationHandler
                },
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
                },
            )

        try {
            val installed =
                assertIs<ProxyServerForegroundRuntimeInstallResult.Installed>(
                    installation.installResult,
                )

            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle.startProxyRuntime()

            val mobileData = installed.managementHandlerReference.handle(ManagementApiOperation.RotateMobileData)
            val airplaneMode = installed.managementHandlerReference.handle(ManagementApiOperation.RotateAirplaneMode)

            assertEquals(202, mobileData.statusCode)
            assertEquals(202, airplaneMode.statusCode)
            assertContains(mobileData.body, """"operation":"mobile_data"""")
            assertContains(airplaneMode.body, """"operation":"airplane_mode"""")
            assertEquals(1, handlerFactoryCalls)
            assertEquals(1, runtimeRotationHandler.mobileDataRotationCalls)
            assertEquals(1, runtimeRotationHandler.airplaneModeRotationCalls)
        } finally {
            installation.close()
            assertTerminates(executors.workerExecutor)
            assertTerminates(executors.queuedClientTimeoutExecutor)
            assertTerminates(executors.acceptLoopExecutor)
        }
    }

    private fun readyBootstrap(): AppConfigBootstrapResult.Ready = AppConfigBootstrapResult.Ready(
        plainConfig =
            AppConfig.default().copy(
                proxy =
                    AppConfig.default().proxy.copy(
                        listenHost = "127.0.0.1",
                        listenPort = 8080,
                    ),
            ),
        sensitiveConfig =
            SensitiveConfig(
                proxyCredential =
                    ProxyCredential(
                        username = "proxy-user",
                        password = "proxy-password",
                    ),
                managementApiToken = "management-token",
            ),
        createdDefaultSecrets = false,
        reconciledPlainConfig = false,
    )

    private fun wifiRoute(): NetworkDescriptor = NetworkDescriptor(
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

private class CompositionRecordingRuntimeRotationRequestHandler : RuntimeRotationRequestHandler {
    var mobileDataRotationCalls = 0
        private set
    var airplaneModeRotationCalls = 0
        private set

    override fun rotateMobileData(): RotationTransitionResult {
        mobileDataRotationCalls += 1
        return acceptedCompletedRotation(RotationOperation.MobileData)
    }

    override fun rotateAirplaneMode(): RotationTransitionResult {
        airplaneModeRotationCalls += 1
        return acceptedCompletedRotation(RotationOperation.AirplaneMode)
    }

    override fun close() = Unit
}

private object CompositionUnavailableBoundNetworkSocketConnector : BoundNetworkSocketConnector {
    override suspend fun connect(
        network: NetworkDescriptor,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult = BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
}

private fun assertTerminates(executor: ExecutorService) {
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
}

private fun assertTerminates(executor: ScheduledExecutorService) {
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
}

private fun acceptedCompletedRotation(operation: RotationOperation): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Accepted,
    status =
        RotationStatus(
            state = RotationState.Completed,
            operation = operation,
            oldPublicIp = "198.51.100.10",
            newPublicIp = "198.51.100.11",
            publicIpChanged = true,
        ),
)
