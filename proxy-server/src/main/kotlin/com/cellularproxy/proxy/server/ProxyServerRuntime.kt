package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStopEvent
import com.cellularproxy.shared.proxy.ProxyServiceStopStateMachine
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

sealed interface ProxyServerRuntimeResult {
    data class StartupFailed(
        val startup: ProxyServerRuntimeStartupResult.Failed,
    ) : ProxyServerRuntimeResult

    data class AcceptLoopLaunchFailed(
        val exception: RejectedExecutionException,
    ) : ProxyServerRuntimeResult

    data class Running(
        val runtime: RunningProxyServerRuntime,
    ) : ProxyServerRuntimeResult
}

sealed interface ProxyServerRuntimeStopResult {
    data class Finished(
        val result: ProxyBoundServerAcceptLoopResult,
    ) : ProxyServerRuntimeStopResult

    data object TimedOut : ProxyServerRuntimeStopResult

    data object Interrupted : ProxyServerRuntimeStopResult

    data class Failed(
        val exception: Exception,
    ) : ProxyServerRuntimeStopResult
}

class RunningProxyServerRuntime internal constructor(
    internal val listener: BoundProxyServerSocket,
    initialStatus: ProxyServiceStatus,
    private val acceptLoop: ProxyBoundServerAcceptLoop,
    private val acceptLoopResult: Future<ProxyBoundServerAcceptLoopResult>,
) : Closeable {
    private val statusReference = AtomicReference(initialStatus)

    val status: ProxyServiceStatus
        get() = statusReference.get()

    init {
        require(initialStatus.state == ProxyServiceState.Running) {
            "Running proxy runtime requires a running service status"
        }
    }

    fun stop() {
        requestStop()
    }

    fun requestStop(): ProxyServiceStopTransitionResult {
        while (true) {
            val current = statusReference.get()
            val result = ProxyServiceStopStateMachine.transition(
                current = current,
                event = ProxyServiceStopEvent.StopRequested,
            )
            if (statusReference.compareAndSet(current, result.status)) {
                if (result.accepted) {
                    acceptLoop.stop(listener)
                }
                return result
            }
        }
    }

    fun awaitStopped(timeoutMillis: Long): ProxyServerRuntimeStopResult {
        require(timeoutMillis > 0) { "Runtime stop timeout must be positive" }
        return try {
            val result = acceptLoopResult.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (result is ProxyBoundServerAcceptLoopResult.Stopped) {
                markStopped()
            }
            ProxyServerRuntimeStopResult.Finished(result)
        } catch (_: TimeoutException) {
            ProxyServerRuntimeStopResult.TimedOut
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ProxyServerRuntimeStopResult.Interrupted
        } catch (exception: ExecutionException) {
            when (val cause = exception.cause) {
                is Error -> throw cause
                is Exception -> ProxyServerRuntimeStopResult.Failed(cause)
                else -> ProxyServerRuntimeStopResult.Failed(exception)
            }
        }
    }

    override fun close() {
        stop()
    }

    private fun markStopped() {
        while (true) {
            val current = statusReference.get()
            if (current.state == ProxyServiceState.Stopped) {
                return
            }
            val stopped = current.copy(state = ProxyServiceState.Stopped)
            if (statusReference.compareAndSet(current, stopped)) {
                return
            }
        }
    }
}

object ProxyServerRuntime {
    fun start(
        config: AppConfig,
        managementApiTokenPresent: Boolean,
        observedNetworks: List<NetworkDescriptor>,
        ingressConfig: ProxyIngressPreflightConfig,
        connectionHandler: ProxyBoundClientConnectionHandler,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        backlog: Int = DEFAULT_RUNTIME_BACKLOG,
        clientHeaderReadIdleTimeoutMillis: Int = DEFAULT_RUNTIME_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS,
        httpBufferSize: Int = DEFAULT_RUNTIME_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_RUNTIME_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_RUNTIME_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_RUNTIME_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_RUNTIME_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerRuntimeResult {
        validateRuntimeTunables(
            clientHeaderReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis,
            httpBufferSize = httpBufferSize,
            maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
            maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
            maxResponseTrailerBytes = maxResponseTrailerBytes,
            connectRelayBufferSize = connectRelayBufferSize,
        )

        val startup = ProxyServerRuntimeStartup.start(
            config = config,
            managementApiTokenPresent = managementApiTokenPresent,
            observedNetworks = observedNetworks,
            backlog = backlog,
            bindListener = bindListener,
        )

        return when (startup) {
            is ProxyServerRuntimeStartupResult.Failed ->
                ProxyServerRuntimeResult.StartupFailed(startup)
            is ProxyServerRuntimeStartupResult.Started ->
                launchAcceptLoop(
                    startup = startup,
                    ingressConfig = ingressConfig,
                    connectionHandler = connectionHandler,
                    workerExecutor = workerExecutor,
                    queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                    acceptLoopExecutor = acceptLoopExecutor,
                    clientHeaderReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis,
                    httpBufferSize = httpBufferSize,
                    maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                    maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                    maxResponseTrailerBytes = maxResponseTrailerBytes,
                    connectRelayBufferSize = connectRelayBufferSize,
                    recordMetricEvent = recordMetricEvent,
                )
        }
    }

    private fun launchAcceptLoop(
        startup: ProxyServerRuntimeStartupResult.Started,
        ingressConfig: ProxyIngressPreflightConfig,
        connectionHandler: ProxyBoundClientConnectionHandler,
        workerExecutor: Executor,
        queuedClientTimeoutExecutor: ScheduledExecutorService,
        acceptLoopExecutor: ExecutorService,
        clientHeaderReadIdleTimeoutMillis: Int,
        httpBufferSize: Int,
        maxOriginResponseHeaderBytes: Int,
        maxResponseChunkHeaderBytes: Int,
        maxResponseTrailerBytes: Int,
        connectRelayBufferSize: Int,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit,
    ): ProxyServerRuntimeResult {
        val acceptLoop = ProxyBoundServerAcceptLoop(
            connectionHandler = connectionHandler,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        )
        val future = try {
            acceptLoopExecutor.submit<ProxyBoundServerAcceptLoopResult> {
                try {
                    acceptLoop.run(
                        listener = startup.listener,
                        config = ingressConfig,
                        clientHeaderReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis,
                        httpBufferSize = httpBufferSize,
                        maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                        maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                        maxResponseTrailerBytes = maxResponseTrailerBytes,
                        connectRelayBufferSize = connectRelayBufferSize,
                        recordMetricEvent = recordMetricEvent,
                    ).also { result ->
                        if (result is ProxyBoundServerAcceptLoopResult.Failed) {
                            startup.listener.closeAfterRuntimeFailure()
                        }
                    }
                } catch (throwable: Throwable) {
                    startup.listener.closeAfterRuntimeFailure()
                    throw throwable
                }
            }
        } catch (exception: RejectedExecutionException) {
            startup.listener.closeAfterLaunchFailure()
            return ProxyServerRuntimeResult.AcceptLoopLaunchFailed(
                exception = exception,
            )
        }

        return ProxyServerRuntimeResult.Running(
            RunningProxyServerRuntime(
                listener = startup.listener,
                initialStatus = startup.status,
                acceptLoop = acceptLoop,
                acceptLoopResult = future,
            ),
        )
    }
}

private fun validateRuntimeTunables(
    clientHeaderReadIdleTimeoutMillis: Int,
    httpBufferSize: Int,
    maxOriginResponseHeaderBytes: Int,
    maxResponseChunkHeaderBytes: Int,
    maxResponseTrailerBytes: Int,
    connectRelayBufferSize: Int,
) {
    require(clientHeaderReadIdleTimeoutMillis > 0) {
        "Client header-read idle timeout must be positive"
    }
    require(httpBufferSize > 0) { "HTTP buffer size must be positive" }
    require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
    require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
    require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }
    require(connectRelayBufferSize > 0) { "CONNECT relay buffer size must be positive" }
}

private fun BoundProxyServerSocket.closeAfterLaunchFailure() {
    try {
        close()
    } catch (_: Exception) {
        // Preserve the executor rejection as the launch failure.
    }
}

private fun BoundProxyServerSocket.closeAfterRuntimeFailure() {
    try {
        close()
    } catch (_: Exception) {
        // Preserve the accept-loop failure as the runtime result.
    }
}

private const val DEFAULT_RUNTIME_BACKLOG = 50
private const val DEFAULT_RUNTIME_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS = 60_000
private const val DEFAULT_RUNTIME_HTTP_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_RUNTIME_ORIGIN_RESPONSE_HEADER_BYTES = 16 * 1024
private const val DEFAULT_RUNTIME_RESPONSE_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_RUNTIME_RESPONSE_TRAILER_BYTES = 16 * 1024
private const val DEFAULT_RUNTIME_CONNECT_RELAY_BUFFER_BYTES = 8 * 1024
