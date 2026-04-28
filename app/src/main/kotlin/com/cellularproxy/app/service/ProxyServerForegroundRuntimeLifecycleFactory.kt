package com.cellularproxy.app.service

import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.ManagementApiAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketProvider
import com.cellularproxy.network.RouteBoundSocketProvider
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStateHandler
import com.cellularproxy.proxy.management.ManagementApiStreamAuditEvent
import com.cellularproxy.proxy.management.ManagementApiStreamAuditOutcome
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyBoundClientConnectionHandler
import com.cellularproxy.proxy.server.ProxyClientStreamExchangeHandler
import com.cellularproxy.proxy.server.ProxyServerRuntime
import com.cellularproxy.proxy.server.ProxyServerRuntimeManagementCallbacks
import com.cellularproxy.proxy.server.ProxyServerRuntimeResult
import com.cellularproxy.proxy.server.ProxyServerRuntimeStartupResult
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.proxy.server.RunningProxyServerRuntime
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStartupDecision
import com.cellularproxy.shared.proxy.ProxyServiceStartupPolicy
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

object ProxyServerForegroundRuntimeLifecycleFactory {
    fun create(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        observedNetworks: () -> List<NetworkDescriptor>,
        socketConnector: BoundNetworkSocketConnector,
        managementHandler: ManagementApiHandler,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle = create(
        plainConfig = plainConfig,
        sensitiveConfig = sensitiveConfig,
        observedNetworks = observedNetworks,
        socketProvider =
            RouteBoundSocketProvider(
                observedNetworks = observedNetworks,
                connector = socketConnector,
            ),
        managementHandler = managementHandler,
        workerExecutor = workerExecutor,
        queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        acceptLoopExecutor = acceptLoopExecutor,
        maxConcurrentConnections = maxConcurrentConnections,
        outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
        recordMetricEvent = recordMetricEvent,
        recordManagementAudit = recordManagementAudit,
        bindListener = bindListener,
    )

    fun create(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        observedNetworks: () -> List<NetworkDescriptor>,
        socketConnector: BoundNetworkSocketConnector,
        managementHandlerReference: RuntimeManagementApiHandlerReference,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        rootOperationsEnabled: () -> Boolean = { plainConfig.root.operationsEnabled },
        rootAvailability: () -> RootAvailabilityStatus,
        runtimeRotationRequestHandlerFactory: (RunningProxyServerRuntime) -> RuntimeRotationRequestHandler? = { null },
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle = create(
        plainConfig = plainConfig,
        sensitiveConfig = sensitiveConfig,
        observedNetworks = observedNetworks,
        socketProvider =
            RouteBoundSocketProvider(
                observedNetworks = observedNetworks,
                connector = socketConnector,
            ),
        managementHandlerReference = managementHandlerReference,
        publicIp = publicIp,
        cloudflareStatus = cloudflareStatus,
        cloudflareStart = cloudflareStart,
        cloudflareStop = cloudflareStop,
        cloudflareReconnect = cloudflareReconnect,
        rotateMobileData = rotateMobileData,
        rotateAirplaneMode = rotateAirplaneMode,
        rootOperationsEnabled = rootOperationsEnabled,
        rootAvailability = rootAvailability,
        runtimeRotationRequestHandlerFactory = runtimeRotationRequestHandlerFactory,
        workerExecutor = workerExecutor,
        queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        acceptLoopExecutor = acceptLoopExecutor,
        maxConcurrentConnections = maxConcurrentConnections,
        outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
        recordMetricEvent = recordMetricEvent,
        recordManagementAudit = recordManagementAudit,
        bindListener = bindListener,
    )

    fun create(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        observedNetworks: () -> List<NetworkDescriptor>,
        socketProvider: BoundSocketProvider,
        managementHandlerReference: RuntimeManagementApiHandlerReference,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        rootOperationsEnabled: () -> Boolean = { plainConfig.root.operationsEnabled },
        rootAvailability: () -> RootAvailabilityStatus,
        runtimeRotationRequestHandlerFactory: (RunningProxyServerRuntime) -> RuntimeRotationRequestHandler? = { null },
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle = create(
        plainConfig = plainConfig,
        sensitiveConfig = sensitiveConfig,
        observedNetworks = observedNetworks,
        socketProvider = socketProvider,
        managementHandler = managementHandlerReference,
        workerExecutor = workerExecutor,
        queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        acceptLoopExecutor = acceptLoopExecutor,
        maxConcurrentConnections = maxConcurrentConnections,
        outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
        recordMetricEvent = recordMetricEvent,
        recordManagementAudit = recordManagementAudit,
        bindListener = bindListener,
        installRuntimeManagementHandler = { runtime ->
            var runtimeRotationRequestHandler: RuntimeRotationRequestHandler? = null
            try {
                runtimeRotationRequestHandler = runtimeRotationRequestHandlerFactory(runtime)
                val rotateMobileDataRequest =
                    runtimeRotationRequestHandler?.let { it::rotateMobileData } ?: rotateMobileData
                val rotateAirplaneModeRequest =
                    runtimeRotationRequestHandler?.let { it::rotateAirplaneMode } ?: rotateAirplaneMode
                val rotateMobileDataIfRootEnabled =
                    rotateMobileDataRequest.guardRootOperations(
                        rootOperationsEnabled = rootOperationsEnabled,
                        operation = RotationOperation.MobileData,
                    )
                val rotateAirplaneModeIfRootEnabled =
                    rotateAirplaneModeRequest.guardRootOperations(
                        rootOperationsEnabled = rootOperationsEnabled,
                        operation = RotationOperation.AirplaneMode,
                    )
                val stateHandler =
                    ManagementApiStateHandler(
                        callbacks =
                            ProxyServerRuntimeManagementCallbacks.create(
                                runtime = runtime,
                                networks = observedNetworks,
                                publicIp = publicIp,
                                cloudflareStatus = cloudflareStatus,
                                cloudflareStart = cloudflareStart,
                                cloudflareStop = cloudflareStop,
                                cloudflareReconnect = cloudflareReconnect,
                                rootOperationsEnabled = rootOperationsEnabled,
                                rootAvailability = rootAvailability,
                                rotateMobileData = rotateMobileDataIfRootEnabled,
                                rotateAirplaneMode = rotateAirplaneModeIfRootEnabled,
                            ),
                        secrets = sensitiveConfig.logRedactionSecrets(),
                    )
                val installedHandler =
                    runtimeRotationRequestHandler?.let {
                        CloseableManagementApiHandler(
                            delegate = stateHandler,
                            closeable = it,
                        )
                    } ?: stateHandler
                managementHandlerReference.install(installedHandler)
            } catch (throwable: Throwable) {
                runtimeRotationRequestHandler?.closeSuppressingExceptions()
                throw throwable
            }
        },
    )

    fun create(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        observedNetworks: () -> List<NetworkDescriptor>,
        socketProvider: BoundSocketProvider,
        managementHandler: ManagementApiHandler,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        installRuntimeManagementHandler: (RunningProxyServerRuntime) -> Closeable? = { null },
    ): ProxyServerForegroundRuntimeLifecycle {
        val outboundConnectors =
            ProxyRuntimeOutboundConnectorFactory.create(
                route = plainConfig.network.defaultRoutePolicy,
                socketProvider = socketProvider,
                connectTimeoutMillis = outboundConnectTimeoutMillis,
            )
        return create(
            plainConfig = plainConfig,
            sensitiveConfig = sensitiveConfig,
            observedNetworks = observedNetworks,
            connectionHandler =
                ProxyBoundClientConnectionHandler(
                    exchangeHandler =
                        ProxyClientStreamExchangeHandler(
                            httpConnector = outboundConnectors.httpConnector,
                            connectConnector = outboundConnectors.connectConnector,
                            managementHandler = managementHandler,
                            recordManagementAudit = { recordManagementAudit(it.toAppManagementAuditRecord()) },
                        ),
                ),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            maxConcurrentConnections = maxConcurrentConnections,
            recordMetricEvent = recordMetricEvent,
            bindListener = bindListener,
            installRuntimeManagementHandler = installRuntimeManagementHandler,
        )
    }

    fun create(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        observedNetworks: () -> List<NetworkDescriptor>,
        connectionHandler: ProxyBoundClientConnectionHandler,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        installRuntimeManagementHandler: (RunningProxyServerRuntime) -> Closeable? = { null },
    ): ProxyServerForegroundRuntimeLifecycle = ProxyServerForegroundRuntimeLifecycle(
        startRuntime = startRuntime@{
            val runtimeConfig = plainConfig.reconcileCloudflareTokenPresence(sensitiveConfig)
            val effectiveRuntimeConfig =
                runtimeConfig.copy(
                    proxy =
                        runtimeConfig.proxy.copy(
                            maxConcurrentConnections = maxConcurrentConnections,
                        ),
                )
            val startupNetworks = observedNetworks()
            when (
                val startup =
                    ProxyServiceStartupPolicy.evaluate(
                        config = effectiveRuntimeConfig,
                        managementApiTokenPresent = true,
                        observedNetworks = startupNetworks,
                    )
            ) {
                is ProxyServiceStartupDecision.Failed ->
                    return@startRuntime ProxyServerRuntimeResult.StartupFailed(
                        ProxyServerRuntimeStartupResult.Failed(
                            startupError = startup.startupError,
                            status = startup.status,
                        ),
                    )

                is ProxyServiceStartupDecision.Ready -> Unit
            }
            ProxyServerRuntime.start(
                config = effectiveRuntimeConfig,
                managementApiTokenPresent = true,
                observedNetworks = startupNetworks,
                ingressConfig =
                    ProxyRuntimeIngressConfigFactory.from(
                        plainConfig = effectiveRuntimeConfig,
                        sensitiveConfig = sensitiveConfig,
                        maxConcurrentConnections = maxConcurrentConnections,
                    ),
                connectionHandler = connectionHandler,
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                recordMetricEvent = recordMetricEvent,
                bindListener = bindListener,
            )
        },
        onRuntimeStarted = installRuntimeManagementHandler,
    )
}

private fun AppConfig.reconcileCloudflareTokenPresence(sensitiveConfig: SensitiveConfig): AppConfig = copy(
    cloudflare =
        cloudflare.copy(
            tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
        ),
)

private fun SensitiveConfig.logRedactionSecrets(): LogRedactionSecrets = LogRedactionSecrets(
    managementApiToken = managementApiToken,
    proxyCredential = proxyCredential.canonicalBasicPayload(),
    cloudflareTunnelToken = cloudflareTunnelToken,
)

private fun ManagementApiStreamAuditEvent.toAppManagementAuditRecord(): ManagementApiAuditRecord = ManagementApiAuditRecord(
    operation = operation,
    outcome =
        when (outcome) {
            ManagementApiStreamAuditOutcome.Responded -> ManagementApiAuditOutcome.Responded
            ManagementApiStreamAuditOutcome.RouteRejected -> ManagementApiAuditOutcome.RouteRejected
            ManagementApiStreamAuditOutcome.HandlerFailed -> ManagementApiAuditOutcome.HandlerFailed
            ManagementApiStreamAuditOutcome.AuthorizationRejected -> ManagementApiAuditOutcome.AuthorizationRejected
        },
    statusCode = statusCode,
    disposition = disposition,
)

private fun (() -> RotationTransitionResult).guardRootOperations(
    rootOperationsEnabled: () -> Boolean,
    operation: RotationOperation,
): () -> RotationTransitionResult = {
    if (rootOperationsEnabled()) {
        this()
    } else {
        rootOperationsDisabledRotationTransition(operation)
    }
}

private fun rootOperationsDisabledRotationTransition(operation: RotationOperation): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Rejected,
    status =
        RotationStatus(
            state = RotationState.Failed,
            operation = operation,
            failureReason = RotationFailureReason.RootOperationsDisabled,
        ),
)

private class CloseableManagementApiHandler(
    private val delegate: ManagementApiHandler,
    private val closeable: Closeable,
) : ManagementApiHandler,
    Closeable {
    override fun handle(operation: ManagementApiOperation): ManagementApiResponse = delegate.handle(operation)

    override fun close() {
        closeable.close()
    }
}

private fun Closeable.closeSuppressingExceptions() {
    try {
        close()
    } catch (_: Throwable) {
        // Startup failure cleanup must not hide the original installation error.
    }
}

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = CloudflareTunnelStatus.disabled(),
)

private const val DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
