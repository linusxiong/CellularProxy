package com.cellularproxy.app.service

import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.cloudflare.CloudflareTunnelEdgeConnectionResult
import com.cellularproxy.cloudflare.CloudflareTunnelEdgeConnector
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.network.PublicIpProbeResponseFormat
import com.cellularproxy.network.PublicIpProbeScheme
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
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
    fun `default public IP probe uses ipify HTTPS endpoint`() {
        val endpoint = defaultPublicIpProbeEndpoint()

        assertEquals("api.ipify.org", endpoint.host)
        assertEquals(PublicIpProbeScheme.Https, endpoint.scheme)
        assertEquals(443, endpoint.port)
        assertEquals("/", endpoint.path)
        assertEquals(PublicIpProbeResponseFormat.PlainText, endpoint.responseFormat)
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
    fun `default production rotation handler checks root availability before rotation work`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val auditRecords = mutableListOf<RootCommandAuditRecord>()
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                rootOperationsEnabled = { true },
                rootCommandProcessExecutor = { _, _ ->
                    com.cellularproxy.root.RootCommandProcessResult.Completed(
                        exitCode = 0,
                        stdout = "2000",
                        stderr = "",
                    )
                },
                recordRootAudit = auditRecords::add,
                nowElapsedMillis = { 0L },
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

            assertEquals(202, mobileData.statusCode)
            assertContains(mobileData.body, """"disposition":"accepted"""")
            assertContains(mobileData.body, """"state":"failed"""")
            assertContains(mobileData.body, """"operation":"mobile_data"""")
            assertContains(mobileData.body, """"failureReason":"root_unavailable"""")
            assertEquals(
                listOf(
                    RootCommandCategory.RootAvailabilityCheck,
                    RootCommandCategory.RootAvailabilityCheck,
                ),
                auditRecords.map { it.category },
            )
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

    @Test
    fun `default production service restart action schedules root restart command after accepting response`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val auditRecords = mutableListOf<RootCommandAuditRecord>()
        val restartAuditCompleted = CountDownLatch(1)
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                rootOperationsEnabled = { true },
                rootCommandProcessExecutor = { _, _ ->
                    com.cellularproxy.root.RootCommandProcessResult.Completed(
                        exitCode = 0,
                        stdout = "restarted",
                        stderr = "",
                    )
                },
                recordRootAudit = { auditRecord ->
                    auditRecords += auditRecord
                    if (auditRecord.phase == RootCommandAuditPhase.Completed) {
                        restartAuditCompleted.countDown()
                    }
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

            val response = installed.managementHandlerReference.handle(ManagementApiOperation.ServiceRestart)

            assertEquals(202, response.statusCode)
            assertEquals("""{"accepted":true,"restart":{"packageName":"com.cellularproxy","failureReason":null}}""", response.body)
            assertEquals(emptyList(), auditRecords)
            response.notifyResponseSent()
            assertTrue(restartAuditCompleted.await(1, TimeUnit.SECONDS))
            assertEquals(
                listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed),
                auditRecords.map { it.phase },
            )
            assertEquals(
                listOf(RootCommandCategory.ServiceRestart, RootCommandCategory.ServiceRestart),
                auditRecords.map { it.category },
            )
            assertEquals(RootCommandOutcome.Success, auditRecords.last().outcome)
        } finally {
            installation.close()
            assertTerminates(executors.workerExecutor)
            assertTerminates(executors.queuedClientTimeoutExecutor)
            assertTerminates(executors.acceptLoopExecutor)
        }
    }

    @Test
    fun `production cloudflare runtime shares control plane and edge session registry`() {
        var closedConnections = 0
        val runtime =
            createProductionCloudflareTunnelRuntime(
                plainConfig =
                    readyBootstrap().plainConfig.copy(
                        cloudflare = AppConfig.default().cloudflare.copy(enabled = true),
                    ),
                sensitiveConfig =
                    readyBootstrap().sensitiveConfig.copy(
                        cloudflareTunnelToken = validCloudflareTunnelToken(),
                    ),
                edgeConnector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Connected(
                            connection = { closedConnections += 1 },
                        )
                    },
            )

        val start = runtime.start()

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, start.disposition)
        assertEquals(CloudflareTunnelState.Connected, runtime.status().state)
        assertEquals(
            "Active edge session: Connected (generation 2)",
            runtime.edgeSessionSummary(),
        )

        val stop = runtime.stop()

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, stop.disposition)
        assertEquals(CloudflareTunnelState.Stopped, runtime.status().state)
        assertEquals(null, runtime.edgeSessionSummary())
        assertEquals(1, closedConnections)
    }

    @Test
    fun `production cloudflare runtime reconnect replaces connected edge session`() {
        val closedConnections = mutableListOf<String>()
        var connectionIndex = 0
        val runtime =
            createProductionCloudflareTunnelRuntime(
                plainConfig =
                    readyBootstrap().plainConfig.copy(
                        cloudflare = AppConfig.default().cloudflare.copy(enabled = true),
                    ),
                sensitiveConfig =
                    readyBootstrap().sensitiveConfig.copy(
                        cloudflareTunnelToken = validCloudflareTunnelToken(),
                    ),
                edgeConnector =
                    CloudflareTunnelEdgeConnector {
                        connectionIndex += 1
                        val connectionId = "connection-$connectionIndex"
                        CloudflareTunnelEdgeConnectionResult.Connected(
                            connection = { closedConnections += connectionId },
                        )
                    },
            )

        val start = runtime.start()
        val reconnect = runtime.reconnect()
        val stop = runtime.stop()

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, start.disposition)
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, reconnect.disposition)
        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, stop.disposition)
        assertEquals(
            listOf("connection-1", "connection-2"),
            closedConnections,
        )
    }

    @Test
    fun `production cloudflare runtime close releases active edge session`() {
        var closedConnections = 0
        val runtime =
            createProductionCloudflareTunnelRuntime(
                plainConfig =
                    readyBootstrap().plainConfig.copy(
                        cloudflare = AppConfig.default().cloudflare.copy(enabled = true),
                    ),
                sensitiveConfig =
                    readyBootstrap().sensitiveConfig.copy(
                        cloudflareTunnelToken = validCloudflareTunnelToken(),
                    ),
                edgeConnector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Connected(
                            connection = { closedConnections += 1 },
                        )
                    },
            )

        runtime.start()
        runtime.close()
        runtime.close()

        assertEquals(null, runtime.edgeSessionSummary())
        assertEquals(1, closedConnections)
    }

    @Test
    fun `installation close releases owned cloudflare runtime cleanup`() {
        val routeMonitor = RecordingRouteMonitor(listOf(wifiRoute()))
        val executors =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newSingleThreadExecutor(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
            )
        val cloudflareCleanup = CompositionRecordingCloseable()
        val installation =
            CellularProxyRuntimeCompositionInstaller.installForTesting(
                bootstrapResult = readyBootstrap(),
                observedNetworks = routeMonitor::observedNetworks,
                routeMonitor = routeMonitor,
                socketConnector = CompositionUnavailableBoundNetworkSocketConnector,
                executorResources = executors,
                cloudflareRuntimeCleanup = cloudflareCleanup,
            )

        installation.close()

        assertTrue(cloudflareCleanup.closed)
        assertTerminates(executors.workerExecutor)
        assertTerminates(executors.queuedClientTimeoutExecutor)
        assertTerminates(executors.acceptLoopExecutor)
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

    private fun validCloudflareTunnelToken(): String {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
        val json = """{"a":"account-tag","s":"$secret","t":"$tunnelId","e":"edge.example.com"}"""
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
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

private class CompositionRecordingCloseable : Closeable {
    var closed: Boolean = false
        private set

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
