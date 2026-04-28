package com.cellularproxy.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.cellularproxy.app.audit.CellularProxyManagementAuditStore
import com.cellularproxy.app.audit.CellularProxyRootAuditStore
import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.AppConfigBootstrapper
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.network.AndroidBoundNetworkSocketConnector
import com.cellularproxy.app.network.AndroidNetworkRouteMonitor
import com.cellularproxy.cloudflare.CloudflareTunnelConnectionCoordinatorResult
import com.cellularproxy.cloudflare.CloudflareTunnelEdgeConnector
import com.cellularproxy.cloudflare.CloudflareTunnelEdgeSessionRegistry
import com.cellularproxy.cloudflare.CloudflareTunnelReconnectCoordinator
import com.cellularproxy.cloudflare.CloudflareTunnelReconnectCoordinatorResult
import com.cellularproxy.cloudflare.CloudflareTunnelStartAndConnectCoordinator
import com.cellularproxy.cloudflare.CloudflareTunnelStartAndConnectCoordinatorResult
import com.cellularproxy.cloudflare.CloudflareTunnelStartCoordinatorResult
import com.cellularproxy.cloudflare.CloudflareTunnelStartupDecision
import com.cellularproxy.cloudflare.CloudflareTunnelStartupPolicy
import com.cellularproxy.cloudflare.CloudflareTunnelStopCoordinator
import com.cellularproxy.cloudflare.CloudflareTunnelStopCoordinatorResult
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.PublicIpProbeEndpoint
import com.cellularproxy.network.RouteBoundPublicIpProbe
import com.cellularproxy.network.RouteBoundSocketProvider
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyRotationPauseActions
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.proxy.server.RunningProxyServerRuntime
import com.cellularproxy.root.AirplaneModeRootController
import com.cellularproxy.root.BlockingRootCommandProcessExecutor
import com.cellularproxy.root.MobileDataRootController
import com.cellularproxy.root.RootAvailabilityChecker
import com.cellularproxy.root.RootCommandExecutor
import com.cellularproxy.root.RootCommandProcessExecutor
import com.cellularproxy.root.RotationRootCommandController
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class CellularProxyRuntimeCompositionInstallation internal constructor(
    val installResult: ProxyServerForegroundRuntimeInstallResult,
    private val cleanup: Closeable,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        cleanup.close()
    }
}

object CellularProxyRuntimeCompositionInstaller {
    fun install(
        context: Context,
        runtimeRotationRequestHandlerFactory: ((RunningProxyServerRuntime) -> RuntimeRotationRequestHandler?)? = null,
        onRuntimeStatusAvailable: (NotificationRuntimeStatus) -> Unit = {},
    ): CellularProxyRuntimeCompositionInstallation {
        val appContext = context.applicationContext
        val plainRepository = CellularProxyPlainConfigStore.repository(appContext)
        val bootstrapResult =
            runBlocking {
                AppConfigBootstrapper(
                    plainRepository = plainRepository,
                    sensitiveRepository = SensitiveConfigRepositoryFactory.create(appContext),
                ).loadOrCreate()
            }
        val routeMonitor = AndroidNetworkRouteMonitor.create(appContext)
        val rootOperationsEnabled = { runBlocking { plainRepository.load().root.operationsEnabled } }
        val rootAuditLog = CellularProxyRootAuditStore.rootCommandAuditLog(appContext)
        val recordRootAudit =
            nonFatalRootAuditRecorder(
                recordRootAudit = rootAuditLog::record,
                reportRootAuditFailure = ::logRootAuditFailure,
            )
        val managementAuditLog = CellularProxyManagementAuditStore.managementApiAuditLog(appContext)
        val rootCommandProcessExecutor = BlockingRootCommandProcessExecutor()
        val productionCloudflareRuntime =
            when (bootstrapResult) {
                is AppConfigBootstrapResult.Ready ->
                    createProductionCloudflareTunnelRuntime(
                        plainConfig = bootstrapResult.plainConfig,
                        sensitiveConfig = bootstrapResult.sensitiveConfig,
                    )
                is AppConfigBootstrapResult.InvalidSensitiveConfig -> null
            }
        return install(
            bootstrapResult = bootstrapResult,
            observedNetworks = routeMonitor::observedNetworks,
            routeMonitor = routeMonitor,
            socketConnector = AndroidBoundNetworkSocketConnector.create(appContext),
            executorResources = RuntimeCompositionExecutorResources.create(),
            cloudflareStatus = productionCloudflareRuntime?.status ?: { CloudflareTunnelStatus.disabled() },
            cloudflareEdgeSessionSummary = productionCloudflareRuntime?.edgeSessionSummary ?: { null },
            cloudflareStart = productionCloudflareRuntime?.start ?: ::ignoredCloudflareTransition,
            cloudflareStop = productionCloudflareRuntime?.stop ?: ::ignoredCloudflareTransition,
            cloudflareReconnect = productionCloudflareRuntime?.reconnect ?: ::ignoredCloudflareTransition,
            rootOperationsEnabled = rootOperationsEnabled,
            rootCommandProcessExecutor = rootCommandProcessExecutor,
            recordRootAudit = recordRootAudit,
            runtimeRotationRequestHandlerFactory = runtimeRotationRequestHandlerFactory,
            rootAvailability =
                createRootAvailabilityProvider(
                    rootOperationsEnabled = rootOperationsEnabled,
                    processExecutor = rootCommandProcessExecutor,
                    recordRootAudit = recordRootAudit,
                ),
            recordManagementAudit =
                nonFatalManagementAuditRecorder(
                    recordManagementAudit = managementAuditLog::record,
                ),
            onRuntimeStatusAvailable = onRuntimeStatusAvailable,
        )
    }

    internal fun installForTesting(
        bootstrapResult: AppConfigBootstrapResult,
        observedNetworks: () -> List<NetworkDescriptor>,
        routeMonitor: Closeable,
        socketConnector: BoundNetworkSocketConnector,
        executorResources: RuntimeCompositionExecutorResources,
        publicIp: () -> String? = { null },
        cloudflareStatus: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
        cloudflareEdgeSessionSummary: () -> String? = { null },
        cloudflareStart: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareStop: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult = {
            unavailableRotationExecutionTransition(RotationOperation.MobileData)
        },
        rotateAirplaneMode: () -> RotationTransitionResult = {
            unavailableRotationExecutionTransition(RotationOperation.AirplaneMode)
        },
        rootOperationsEnabled: () -> Boolean = {
            (bootstrapResult as? AppConfigBootstrapResult.Ready)?.plainConfig?.root?.operationsEnabled == true
        },
        rootCommandProcessExecutor: RootCommandProcessExecutor = BlockingRootCommandProcessExecutor(),
        recordRootAudit: (RootCommandAuditRecord) -> Unit = {},
        rootAvailability: () -> RootAvailabilityStatus = ::unknownRootAvailability,
        runtimeRotationRequestHandlerFactory: ((RunningProxyServerRuntime) -> RuntimeRotationRequestHandler?)? = null,
        nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
        maxConcurrentConnections: Int? = null,
        outboundConnectTimeoutMillis: Long = COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (com.cellularproxy.app.audit.ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        onRuntimeStatusAvailable: (NotificationRuntimeStatus) -> Unit = {},
    ): CellularProxyRuntimeCompositionInstallation = install(
        bootstrapResult = bootstrapResult,
        observedNetworks = observedNetworks,
        routeMonitor = routeMonitor,
        socketConnector = socketConnector,
        executorResources = executorResources,
        publicIp = publicIp,
        cloudflareStatus = cloudflareStatus,
        cloudflareEdgeSessionSummary = cloudflareEdgeSessionSummary,
        cloudflareStart = cloudflareStart,
        cloudflareStop = cloudflareStop,
        cloudflareReconnect = cloudflareReconnect,
        rotateMobileData = rotateMobileData,
        rotateAirplaneMode = rotateAirplaneMode,
        rootOperationsEnabled = rootOperationsEnabled,
        rootCommandProcessExecutor = rootCommandProcessExecutor,
        recordRootAudit = recordRootAudit,
        rootAvailability = rootAvailability,
        runtimeRotationRequestHandlerFactory = runtimeRotationRequestHandlerFactory,
        nowElapsedMillis = nowElapsedMillis,
        maxConcurrentConnections = maxConcurrentConnections,
        outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
        recordMetricEvent = recordMetricEvent,
        recordManagementAudit = recordManagementAudit,
        bindListener = bindListener,
        onRuntimeStatusAvailable = onRuntimeStatusAvailable,
    )

    private fun install(
        bootstrapResult: AppConfigBootstrapResult,
        observedNetworks: () -> List<NetworkDescriptor>,
        routeMonitor: Closeable,
        socketConnector: BoundNetworkSocketConnector,
        executorResources: RuntimeCompositionExecutorResources,
        publicIp: () -> String? = { null },
        cloudflareStatus: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
        cloudflareEdgeSessionSummary: () -> String? = { null },
        cloudflareStart: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareStop: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult = {
            unavailableRotationExecutionTransition(RotationOperation.MobileData)
        },
        rotateAirplaneMode: () -> RotationTransitionResult = {
            unavailableRotationExecutionTransition(RotationOperation.AirplaneMode)
        },
        rootOperationsEnabled: () -> Boolean = {
            (bootstrapResult as? AppConfigBootstrapResult.Ready)?.plainConfig?.root?.operationsEnabled == true
        },
        rootCommandProcessExecutor: RootCommandProcessExecutor = BlockingRootCommandProcessExecutor(),
        recordRootAudit: (RootCommandAuditRecord) -> Unit = {},
        rootAvailability: () -> RootAvailabilityStatus,
        runtimeRotationRequestHandlerFactory: ((RunningProxyServerRuntime) -> RuntimeRotationRequestHandler?)? = null,
        nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
        maxConcurrentConnections: Int? = null,
        outboundConnectTimeoutMillis: Long = COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (com.cellularproxy.app.audit.ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        onRuntimeStatusAvailable: (NotificationRuntimeStatus) -> Unit = {},
    ): CellularProxyRuntimeCompositionInstallation {
        val installResult =
            try {
                ProxyServerForegroundRuntimeInstaller.install(
                    bootstrapResult = bootstrapResult,
                    observedNetworks = observedNetworks,
                    socketConnector = socketConnector,
                    publicIp = publicIp,
                    cloudflareStatus = cloudflareStatus,
                    cloudflareEdgeSessionSummary = cloudflareEdgeSessionSummary,
                    cloudflareStart = cloudflareStart,
                    cloudflareStop = cloudflareStop,
                    cloudflareReconnect = cloudflareReconnect,
                    rotateMobileData = rotateMobileData,
                    rotateAirplaneMode = rotateAirplaneMode,
                    rootOperationsEnabled = rootOperationsEnabled,
                    rootAvailability = rootAvailability,
                    runtimeRotationRequestHandlerFactory =
                        runtimeRotationRequestHandlerFactory
                            ?: when (bootstrapResult) {
                                is AppConfigBootstrapResult.Ready ->
                                    createProductionRuntimeRotationRequestHandlerFactory(
                                        plainConfig = bootstrapResult.plainConfig,
                                        sensitiveConfig = bootstrapResult.sensitiveConfig,
                                        observedNetworks = observedNetworks,
                                        socketConnector = socketConnector,
                                        rootCommandProcessExecutor = rootCommandProcessExecutor,
                                        recordRootAudit = recordRootAudit,
                                        continuationExecutor = executorResources.rotationContinuationExecutor,
                                        nowElapsedMillis = nowElapsedMillis,
                                    )
                                is AppConfigBootstrapResult.InvalidSensitiveConfig -> {
                                    { null }
                                }
                            },
                    workerExecutor = executorResources.workerExecutor,
                    queuedClientTimeoutExecutor = executorResources.queuedClientTimeoutExecutor,
                    acceptLoopExecutor = executorResources.acceptLoopExecutor,
                    maxConcurrentConnections = maxConcurrentConnections,
                    outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
                    recordMetricEvent = recordMetricEvent,
                    recordManagementAudit = recordManagementAudit,
                    bindListener = bindListener,
                    onRuntimeStatusAvailable = onRuntimeStatusAvailable,
                )
            } catch (throwable: Throwable) {
                RuntimeCompositionCleanup(
                    installResult = null,
                    routeMonitor = routeMonitor,
                    executorResources = executorResources,
                ).close()
                throw throwable
            }
        val cleanup =
            RuntimeCompositionCleanup(
                installResult = installResult,
                routeMonitor = routeMonitor,
                executorResources = executorResources,
            )
        if (installResult is ProxyServerForegroundRuntimeInstallResult.InvalidSensitiveConfig) {
            cleanup.close()
        }

        return CellularProxyRuntimeCompositionInstallation(
            installResult = installResult,
            cleanup = cleanup,
        )
    }
}

internal class ProductionCloudflareTunnelRuntime(
    val status: () -> CloudflareTunnelStatus,
    val edgeSessionSummary: () -> String?,
    val start: () -> CloudflareTunnelTransitionResult,
    val stop: () -> CloudflareTunnelTransitionResult,
    val reconnect: () -> CloudflareTunnelTransitionResult,
)

internal fun createProductionCloudflareTunnelRuntime(
    plainConfig: AppConfig,
    sensitiveConfig: SensitiveConfig,
    edgeConnector: CloudflareTunnelEdgeConnector? = null,
): ProductionCloudflareTunnelRuntime {
    val controlPlane = CloudflareTunnelControlPlane()
    val sessionRegistry = CloudflareTunnelEdgeSessionRegistry()

    return ProductionCloudflareTunnelRuntime(
        status = controlPlane::currentStatus,
        edgeSessionSummary = sessionRegistry::activeSessionSummaryOrNull,
        start = {
            val connector =
                edgeConnector ?: return@ProductionCloudflareTunnelRuntime ignoredCloudflareTransition(controlPlane.currentStatus)
            CloudflareTunnelStartAndConnectCoordinator(
                controlPlane = controlPlane,
                connector = connector,
                sessionRegistry = sessionRegistry,
            ).startAndConnectIfEnabled(
                enabled = plainConfig.cloudflare.enabled,
                rawTunnelToken = sensitiveConfig.cloudflareTunnelToken,
            ).transitionResultOrNull()
                ?: ignoredCloudflareTransition(controlPlane.currentStatus)
        },
        stop = {
            CloudflareTunnelStopCoordinator(
                controlPlane = controlPlane,
                sessionRegistry = sessionRegistry,
            ).stopIfCurrent(controlPlane.snapshot()).transitionResultOrCurrent()
        },
        reconnect = {
            val connector =
                edgeConnector ?: return@ProductionCloudflareTunnelRuntime ignoredCloudflareTransition(controlPlane.currentStatus)
            val credentials =
                when (
                    val decision =
                        CloudflareTunnelStartupPolicy.evaluate(
                            enabled = plainConfig.cloudflare.enabled,
                            rawTunnelToken = sensitiveConfig.cloudflareTunnelToken,
                        )
                ) {
                    is CloudflareTunnelStartupDecision.Ready -> decision.credentials
                    CloudflareTunnelStartupDecision.Disabled,
                    is CloudflareTunnelStartupDecision.Failed,
                    -> return@ProductionCloudflareTunnelRuntime ignoredCloudflareTransition(controlPlane.currentStatus)
                }
            CloudflareTunnelReconnectCoordinator(
                controlPlane = controlPlane,
                connector = connector,
                sessionRegistry = sessionRegistry,
            ).reconnectIfDegraded(
                expectedSnapshot = controlPlane.snapshot(),
                credentials = credentials,
            ).transitionResultOrCurrent()
        },
    )
}

private fun CloudflareTunnelStartAndConnectCoordinatorResult.transitionResultOrNull(): CloudflareTunnelTransitionResult? = when (this) {
    is CloudflareTunnelStartAndConnectCoordinatorResult.ConnectionAttempted ->
        when (val result = connectionResult) {
            is CloudflareTunnelConnectionCoordinatorResult.Applied -> result.transition.transition
            is CloudflareTunnelConnectionCoordinatorResult.NoAction -> ignoredCloudflareTransition(result.snapshot.status)
            is CloudflareTunnelConnectionCoordinatorResult.Stale ->
                ignoredCloudflareTransition(result.actualSnapshot.status)
        }
    is CloudflareTunnelStartAndConnectCoordinatorResult.NoConnectionAttempt ->
        when (val result = startResult) {
            is CloudflareTunnelStartCoordinatorResult.Ready -> result.transition.transition
            is CloudflareTunnelStartCoordinatorResult.Disabled -> ignoredCloudflareTransition(result.snapshot.status)
            is CloudflareTunnelStartCoordinatorResult.FailedStartup -> null
        }
}

private fun CloudflareTunnelStopCoordinatorResult.transitionResultOrCurrent(): CloudflareTunnelTransitionResult = when (this) {
    is CloudflareTunnelStopCoordinatorResult.Applied -> transition.transition
    is CloudflareTunnelStopCoordinatorResult.NoAction -> ignoredCloudflareTransition(snapshot.status)
    is CloudflareTunnelStopCoordinatorResult.Stale -> ignoredCloudflareTransition(actualSnapshot.status)
}

private fun CloudflareTunnelReconnectCoordinatorResult.transitionResultOrCurrent(): CloudflareTunnelTransitionResult = when (this) {
    is CloudflareTunnelReconnectCoordinatorResult.Applied -> transition.transition
    is CloudflareTunnelReconnectCoordinatorResult.NoAction -> ignoredCloudflareTransition(snapshot.status)
    is CloudflareTunnelReconnectCoordinatorResult.Stale -> ignoredCloudflareTransition(actualSnapshot.status)
}

internal class RuntimeCompositionExecutorResources(
    val workerExecutor: ExecutorService,
    val queuedClientTimeoutExecutor: ScheduledExecutorService,
    val acceptLoopExecutor: ExecutorService,
    val rotationContinuationExecutor: ScheduledExecutorService = ScheduledThreadPoolExecutor(1),
) : Closeable {
    override fun close() {
        rotationContinuationExecutor.shutdownNow()
        acceptLoopExecutor.shutdownNow()
        queuedClientTimeoutExecutor.shutdownNow()
        workerExecutor.shutdownNow()
    }

    companion object {
        fun create(): RuntimeCompositionExecutorResources = RuntimeCompositionExecutorResources(
            workerExecutor = Executors.newCachedThreadPool(),
            queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
            acceptLoopExecutor = Executors.newSingleThreadExecutor(),
        )
    }
}

internal fun createProductionRuntimeRotationRequestHandlerFactory(
    plainConfig: AppConfig,
    sensitiveConfig: SensitiveConfig,
    observedNetworks: () -> List<NetworkDescriptor>,
    socketConnector: BoundNetworkSocketConnector,
    rootCommandProcessExecutor: RootCommandProcessExecutor,
    recordRootAudit: (RootCommandAuditRecord) -> Unit,
    continuationExecutor: ScheduledExecutorService,
    nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    publicIpProbeEndpoint: PublicIpProbeEndpoint = PublicIpProbeEndpoint(DEFAULT_PUBLIC_IP_PROBE_HOST),
): (RunningProxyServerRuntime) -> RuntimeRotationRequestHandler {
    val rootCommandExecutor =
        RootCommandExecutor(
            processExecutor = rootCommandProcessExecutor,
            recordAudit = recordRootAudit,
        )
    val socketProvider =
        RouteBoundSocketProvider(
            observedNetworks = observedNetworks,
            connector = socketConnector,
        )
    val publicIpProbe = RouteBoundPublicIpProbe(socketProvider)
    val rootAvailabilityProbe = RootAvailabilityChecker(rootCommandExecutor)
    val rootCommandController =
        RotationRootCommandController(
            mobileDataController = MobileDataRootController(rootCommandExecutor),
            airplaneModeController = AirplaneModeRootController(rootCommandExecutor),
        )

    return { runtime ->
        ProxyRotationLifecycleDriver(
            coordinator =
                ProxyRotationExecutionCoordinator(
                    controlPlane = RotationControlPlane(),
                    rootAvailabilityProbe = rootAvailabilityProbe,
                    nowElapsedMillis = nowElapsedMillis,
                    cooldown = plainConfig.rotation.cooldown,
                    rootAvailabilityTimeoutMillis = ROOT_AVAILABILITY_CHECK_TIMEOUT_MILLIS,
                    publicIpProbeRunner = publicIpProbe,
                    route = plainConfig.network.defaultRoutePolicy,
                    publicIpProbeEndpoint = publicIpProbeEndpoint,
                    strictIpChangeRequired = plainConfig.rotation.strictIpChangeRequired,
                    pauseActions =
                        object : ProxyRotationPauseActions {
                            override fun pauseProxyRequests(): RotationEvent.NewRequestsPaused = runtime.pauseProxyRequests()

                            override fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed = runtime.resumeProxyRequests()
                        },
                    activeProxyExchanges = runtime::activeProxyExchanges,
                    maxConnectionDrainTime = DEFAULT_ROTATION_CONNECTION_DRAIN_TIME,
                    rootCommandController = rootCommandController,
                    rootCommandTimeoutMillis = ROOT_ROTATION_COMMAND_TIMEOUT_MILLIS,
                    toggleDelay = plainConfig.rotation.mobileDataOffDelay,
                    availableNetworks = observedNetworks,
                    networkReturnTimeout = plainConfig.rotation.networkReturnTimeout,
                    secrets = { sensitiveConfig.logRedactionSecrets() },
                ),
            continuationExecutor = continuationExecutor,
        )
    }
}

private class RuntimeCompositionCleanup(
    private val installResult: ProxyServerForegroundRuntimeInstallResult?,
    private val routeMonitor: Closeable,
    private val executorResources: RuntimeCompositionExecutorResources,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        var firstFailure: Throwable? = null

        fun closeOwned(close: () -> Unit) {
            try {
                close()
            } catch (throwable: Throwable) {
                if (firstFailure == null) {
                    firstFailure = throwable
                } else {
                    firstFailure?.addSuppressed(throwable)
                }
            }
        }

        if (installResult is ProxyServerForegroundRuntimeInstallResult.Installed) {
            closeOwned(installResult.registration::close)
        }
        closeOwned(routeMonitor::close)
        closeOwned(executorResources::close)

        firstFailure?.let { throw it }
    }
}

private fun SensitiveConfig.logRedactionSecrets(): LogRedactionSecrets = LogRedactionSecrets(
    managementApiToken = managementApiToken,
    proxyCredential = proxyCredential.canonicalBasicPayload(),
    cloudflareTunnelToken = cloudflareTunnelToken,
)

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = CloudflareTunnelStatus.disabled(),
)

private fun ignoredCloudflareTransition(status: CloudflareTunnelStatus): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = status,
)

internal fun unavailableRotationExecutionTransition(operation: RotationOperation): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Rejected,
    status =
        RotationStatus(
            state = RotationState.Failed,
            operation = operation,
            failureReason = RotationFailureReason.ExecutionUnavailable,
        ),
)

internal fun createRootAvailabilityProvider(
    rootOperationsEnabled: () -> Boolean,
    processExecutor: RootCommandProcessExecutor = BlockingRootCommandProcessExecutor(),
    recordRootAudit: (RootCommandAuditRecord) -> Unit = {},
): () -> RootAvailabilityStatus {
    val checker =
        RootAvailabilityChecker(
            RootCommandExecutor(
                processExecutor = processExecutor,
                recordAudit = recordRootAudit,
            ),
        )
    return {
        if (rootOperationsEnabled()) {
            checker.check(ROOT_AVAILABILITY_CHECK_TIMEOUT_MILLIS).status
        } else {
            RootAvailabilityStatus.Unknown
        }
    }
}

internal fun nonFatalRootAuditRecorder(
    recordRootAudit: (RootCommandAuditRecord) -> Unit,
    reportRootAuditFailure: (Exception) -> Unit = ::logRootAuditFailure,
): (RootCommandAuditRecord) -> Unit = { auditRecord ->
    try {
        recordRootAudit(auditRecord)
    } catch (exception: Exception) {
        reportRootAuditFailure(exception)
    }
}

private fun unknownRootAvailability(): RootAvailabilityStatus = RootAvailabilityStatus.Unknown

private fun logRootAuditFailure(exception: Exception) {
    Log.w(ROOT_AUDIT_LOG_TAG, "Failed to persist root command audit record", exception)
}

private const val COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
private const val ROOT_AVAILABILITY_CHECK_TIMEOUT_MILLIS = 2_000L
private const val ROOT_ROTATION_COMMAND_TIMEOUT_MILLIS = 10_000L
private const val ROOT_AUDIT_LOG_TAG = "CellularProxyAudit"
private const val DEFAULT_PUBLIC_IP_PROBE_HOST = "api.ipify.org"
private val DEFAULT_ROTATION_CONNECTION_DRAIN_TIME = 15.seconds
