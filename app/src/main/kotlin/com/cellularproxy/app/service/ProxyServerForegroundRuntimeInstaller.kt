package com.cellularproxy.app.service

import com.cellularproxy.app.audit.ManagementApiAuditRecord
import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.proxy.server.RunningProxyServerRuntime
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

sealed interface ProxyServerForegroundRuntimeInstallResult {
    data class Installed(
        val registration: Closeable,
        val managementHandlerReference: RuntimeManagementApiHandlerReference,
    ) : ProxyServerForegroundRuntimeInstallResult

    data class InvalidSensitiveConfig(
        val reason: SensitiveConfigInvalidReason,
    ) : ProxyServerForegroundRuntimeInstallResult
}

object ProxyServerForegroundRuntimeInstaller {
    fun install(
        bootstrapResult: AppConfigBootstrapResult,
        observedNetworks: () -> List<NetworkDescriptor>,
        socketConnector: BoundNetworkSocketConnector,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        rootOperationsEnabled: () -> Boolean = {
            (bootstrapResult as? AppConfigBootstrapResult.Ready)?.plainConfig?.root?.operationsEnabled == true
        },
        rootAvailability: () -> RootAvailabilityStatus,
        runtimeRotationRequestHandlerFactory: (RunningProxyServerRuntime) -> RuntimeRotationRequestHandler? = { null },
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        maxConcurrentConnections: Int? = null,
        outboundConnectTimeoutMillis: Long = INSTALLER_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        recordManagementAudit: (ManagementApiAuditRecord) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
        onRuntimeStatusAvailable: (NotificationRuntimeStatus) -> Unit = {},
    ): ProxyServerForegroundRuntimeInstallResult = when (bootstrapResult) {
        is AppConfigBootstrapResult.InvalidSensitiveConfig ->
            ProxyServerForegroundRuntimeInstallResult.InvalidSensitiveConfig(bootstrapResult.reason)

        is AppConfigBootstrapResult.Ready -> {
            val managementHandlerReference = RuntimeManagementApiHandlerReference()
            val lifecycle =
                ProxyServerForegroundRuntimeLifecycleFactory.create(
                    plainConfig = bootstrapResult.plainConfig,
                    sensitiveConfig = bootstrapResult.sensitiveConfig,
                    observedNetworks = observedNetworks,
                    socketConnector = socketConnector,
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
                    maxConcurrentConnections =
                        maxConcurrentConnections
                            ?: bootstrapResult.plainConfig.proxy.maxConcurrentConnections,
                    outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
                    recordMetricEvent = recordMetricEvent,
                    recordManagementAudit = recordManagementAudit,
                    bindListener = bindListener,
                    onRuntimeStatusAvailable = onRuntimeStatusAvailable,
                )
            ProxyServerForegroundRuntimeInstallResult.Installed(
                registration = ForegroundProxyRuntimeLifecycleInstaller.install(lifecycle),
                managementHandlerReference = managementHandlerReference,
            )
        }
    }
}

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = CloudflareTunnelStatus.disabled(),
)

private const val INSTALLER_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
