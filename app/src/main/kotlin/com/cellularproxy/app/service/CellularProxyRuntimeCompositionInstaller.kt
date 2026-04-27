package com.cellularproxy.app.service

import android.content.Context
import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.AppConfigBootstrapper
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.network.AndroidBoundNetworkSocketConnector
import com.cellularproxy.app.network.AndroidNetworkRouteMonitor
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

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
    fun install(context: Context): CellularProxyRuntimeCompositionInstallation {
        val appContext = context.applicationContext
        val plainRepository = CellularProxyPlainConfigStore.repository(appContext)
        val bootstrapResult = runBlocking {
            AppConfigBootstrapper(
                plainRepository = plainRepository,
                sensitiveRepository = SensitiveConfigRepositoryFactory.create(appContext),
            ).loadOrCreate()
        }
        val routeMonitor = AndroidNetworkRouteMonitor.create(appContext)
        return install(
            bootstrapResult = bootstrapResult,
            observedNetworks = routeMonitor::observedNetworks,
            routeMonitor = routeMonitor,
            socketConnector = AndroidBoundNetworkSocketConnector.create(appContext),
            executorResources = RuntimeCompositionExecutorResources.create(),
            rootOperationsEnabled = { runBlocking { plainRepository.load().root.operationsEnabled } },
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
        cloudflareStart: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareStop: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult = ::ignoredRotationTransition,
        rotateAirplaneMode: () -> RotationTransitionResult = ::ignoredRotationTransition,
        rootOperationsEnabled: () -> Boolean = {
            (bootstrapResult as? AppConfigBootstrapResult.Ready)?.plainConfig?.root?.operationsEnabled == true
        },
        maxConcurrentConnections: Int? = null,
        outboundConnectTimeoutMillis: Long = COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): CellularProxyRuntimeCompositionInstallation =
        install(
            bootstrapResult = bootstrapResult,
            observedNetworks = observedNetworks,
            routeMonitor = routeMonitor,
            socketConnector = socketConnector,
            executorResources = executorResources,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = rotateMobileData,
            rotateAirplaneMode = rotateAirplaneMode,
            rootOperationsEnabled = rootOperationsEnabled,
            maxConcurrentConnections = maxConcurrentConnections,
            outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
            recordMetricEvent = recordMetricEvent,
            bindListener = bindListener,
        )

    private fun install(
        bootstrapResult: AppConfigBootstrapResult,
        observedNetworks: () -> List<NetworkDescriptor>,
        routeMonitor: Closeable,
        socketConnector: BoundNetworkSocketConnector,
        executorResources: RuntimeCompositionExecutorResources,
        publicIp: () -> String? = { null },
        cloudflareStatus: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
        cloudflareStart: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        cloudflareStop: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult = ::ignoredRotationTransition,
        rotateAirplaneMode: () -> RotationTransitionResult = ::ignoredRotationTransition,
        rootOperationsEnabled: () -> Boolean = {
            (bootstrapResult as? AppConfigBootstrapResult.Ready)?.plainConfig?.root?.operationsEnabled == true
        },
        maxConcurrentConnections: Int? = null,
        outboundConnectTimeoutMillis: Long = COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): CellularProxyRuntimeCompositionInstallation {
        val installResult = try {
            ProxyServerForegroundRuntimeInstaller.install(
                bootstrapResult = bootstrapResult,
                observedNetworks = observedNetworks,
                socketConnector = socketConnector,
                publicIp = publicIp,
                cloudflareStatus = cloudflareStatus,
                cloudflareStart = cloudflareStart,
                cloudflareStop = cloudflareStop,
                rotateMobileData = rotateMobileData,
                rotateAirplaneMode = rotateAirplaneMode,
                rootOperationsEnabled = rootOperationsEnabled,
                workerExecutor = executorResources.workerExecutor,
                queuedClientTimeoutExecutor = executorResources.queuedClientTimeoutExecutor,
                acceptLoopExecutor = executorResources.acceptLoopExecutor,
                maxConcurrentConnections = maxConcurrentConnections,
                outboundConnectTimeoutMillis = outboundConnectTimeoutMillis,
                recordMetricEvent = recordMetricEvent,
                bindListener = bindListener,
            )
        } catch (throwable: Throwable) {
            RuntimeCompositionCleanup(
                installResult = null,
                routeMonitor = routeMonitor,
                executorResources = executorResources,
            ).close()
            throw throwable
        }
        val cleanup = RuntimeCompositionCleanup(
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

internal class RuntimeCompositionExecutorResources(
    val workerExecutor: ExecutorService,
    val queuedClientTimeoutExecutor: ScheduledExecutorService,
    val acceptLoopExecutor: ExecutorService,
) : Closeable {
    override fun close() {
        acceptLoopExecutor.shutdownNow()
        queuedClientTimeoutExecutor.shutdownNow()
        workerExecutor.shutdownNow()
    }

    companion object {
        fun create(): RuntimeCompositionExecutorResources =
            RuntimeCompositionExecutorResources(
                workerExecutor = Executors.newCachedThreadPool(),
                queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1),
                acceptLoopExecutor = Executors.newSingleThreadExecutor(),
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

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult =
    CloudflareTunnelTransitionResult(
        disposition = CloudflareTunnelTransitionDisposition.Ignored,
        status = CloudflareTunnelStatus.disabled(),
    )

private fun ignoredRotationTransition(): RotationTransitionResult =
    RotationTransitionResult(
        disposition = RotationTransitionDisposition.Ignored,
        status = RotationStatus.idle(),
    )

private const val COMPOSITION_DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
