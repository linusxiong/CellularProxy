package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketProvider
import com.cellularproxy.network.RouteBoundSocketProvider
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiStateHandler
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyBoundClientConnectionHandler
import com.cellularproxy.proxy.server.ProxyClientStreamExchangeHandler
import com.cellularproxy.proxy.server.ProxyServerRuntime
import com.cellularproxy.proxy.server.ProxyServerRuntimeManagementCallbacks
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.proxy.server.RunningProxyServerRuntime
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
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
        maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle =
        create(
            plainConfig = plainConfig,
            sensitiveConfig = sensitiveConfig,
            observedNetworks = observedNetworks,
            socketProvider = RouteBoundSocketProvider(
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
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle =
        create(
            plainConfig = plainConfig,
            sensitiveConfig = sensitiveConfig,
            observedNetworks = observedNetworks,
            socketProvider = RouteBoundSocketProvider(
                observedNetworks = observedNetworks,
                connector = socketConnector,
            ),
            managementHandlerReference = managementHandlerReference,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = rotateMobileData,
            rotateAirplaneMode = rotateAirplaneMode,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            maxConcurrentConnections = maxConcurrentConnections,
            outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
            recordMetricEvent = recordMetricEvent,
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
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerForegroundRuntimeLifecycle =
        create(
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
            bindListener = bindListener,
            installRuntimeManagementHandler = { runtime ->
                managementHandlerReference.install(
                    ManagementApiStateHandler(
                        callbacks = ProxyServerRuntimeManagementCallbacks.create(
                            runtime = runtime,
                            networks = observedNetworks,
                            publicIp = publicIp,
                            cloudflareStatus = cloudflareStatus,
                            cloudflareStart = cloudflareStart,
                            cloudflareStop = cloudflareStop,
                            rotateMobileData = rotateMobileData,
                            rotateAirplaneMode = rotateAirplaneMode,
                        ),
                        secrets = sensitiveConfig.logRedactionSecrets(),
                    ),
                )
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
        maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
        outboundConnectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        installRuntimeManagementHandler: (RunningProxyServerRuntime) -> Closeable? = { null },
    ): ProxyServerForegroundRuntimeLifecycle {
        val outboundConnectors = ProxyRuntimeOutboundConnectorFactory.create(
            route = plainConfig.network.defaultRoutePolicy,
            socketProvider = socketProvider,
            connectTimeoutMillis = outboundConnectTimeoutMillis,
        )
        return create(
            plainConfig = plainConfig,
            sensitiveConfig = sensitiveConfig,
            observedNetworks = observedNetworks,
            connectionHandler = ProxyBoundClientConnectionHandler(
                exchangeHandler = ProxyClientStreamExchangeHandler(
                    httpConnector = outboundConnectors.httpConnector,
                    connectConnector = outboundConnectors.connectConnector,
                    managementHandler = managementHandler,
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
        maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        installRuntimeManagementHandler: (RunningProxyServerRuntime) -> Closeable? = { null },
    ): ProxyServerForegroundRuntimeLifecycle =
        ProxyServerForegroundRuntimeLifecycle(
            startRuntime = {
                val runtimeConfig = plainConfig.reconcileCloudflareTokenPresence(sensitiveConfig)
                ProxyServerRuntime.start(
                    config = runtimeConfig,
                    managementApiTokenPresent = true,
                    observedNetworks = observedNetworks(),
                    ingressConfig = ProxyRuntimeIngressConfigFactory.from(
                        plainConfig = runtimeConfig,
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

private fun AppConfig.reconcileCloudflareTokenPresence(
    sensitiveConfig: SensitiveConfig,
): AppConfig =
    copy(
        cloudflare = cloudflare.copy(
            tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
        ),
    )

private fun SensitiveConfig.logRedactionSecrets(): LogRedactionSecrets =
    LogRedactionSecrets(
        managementApiToken = managementApiToken,
        proxyCredential = proxyCredential.canonicalBasicPayload(),
        cloudflareTunnelToken = cloudflareTunnelToken,
    )

private const val DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
