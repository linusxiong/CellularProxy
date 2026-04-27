package com.cellularproxy.app.service

import com.cellularproxy.proxy.server.ProxyBoundServerAcceptLoopResult
import com.cellularproxy.proxy.server.ProxyServerRuntimeResult
import com.cellularproxy.proxy.server.ProxyServerRuntimeStopResult
import com.cellularproxy.proxy.server.RunningProxyServerRuntime
import com.cellularproxy.shared.proxy.ProxyStartupError
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Marker for foreground runtime lifecycles whose start path already returns
 * quickly and whose startup failures must remain synchronous to the foreground
 * service command executor.
 */
interface SynchronousForegroundProxyRuntimeLifecycle : ForegroundProxyRuntimeLifecycle

class ProxyServerForegroundRuntimeStartException(
    val startupError: ProxyStartupError?,
    message: String,
    cause: Exception? = null,
) : RuntimeException(message, cause)

class ProxyServerForegroundRuntimeStopException(
    val stopResult: ProxyServerRuntimeStopResult,
) : RuntimeException("Proxy runtime stop failed: $stopResult")

class ProxyServerForegroundRuntimeStopWorkerException(
    cause: Throwable,
) : RuntimeException("Proxy runtime stop worker failed", cause)

class ProxyServerForegroundRuntimeLifecycle(
    private val startRuntime: () -> ProxyServerRuntimeResult,
    private val onRuntimeStarted: (RunningProxyServerRuntime) -> Closeable? = { null },
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
    private val stopExecutor: java.util.concurrent.Executor =
        Executors.newSingleThreadExecutor(ProxyRuntimeStopThreadFactory),
) : SynchronousForegroundProxyRuntimeLifecycle,
    Closeable {
    private val lock = Any()
    private var runningRuntime: RunningProxyServerRuntime? = null
    private var runningRuntimeRegistration: Closeable? = null
    private var closed = false
    private var stopInProgress = false
    private var stopCompletionLatch: CountDownLatch? = null

    val hasRunningRuntime: Boolean
        get() = synchronized(lock) { runningRuntime != null }

    val hasPendingStop: Boolean
        get() = synchronized(lock) { stopInProgress }

    var lastStopFailure: ProxyServerRuntimeStopResult? = null
        private set

    var lastStopWorkerFailure: Throwable? = null
        private set

    init {
        require(stopTimeoutMillis > 0) { "Runtime stop timeout must be positive" }
    }

    override fun startProxyRuntime() {
        synchronized(lock) {
            check(!closed) { "Proxy foreground runtime lifecycle is closed" }
            check(!stopInProgress) { "Proxy runtime stop is still in progress" }
            if (runningRuntime != null) {
                return
            }

            when (val result = startRuntime()) {
                is ProxyServerRuntimeResult.Running -> {
                    runningRuntimeRegistration =
                        try {
                            onRuntimeStarted(result.runtime)
                        } catch (throwable: Throwable) {
                            result.runtime.requestStop()
                            result.runtime.awaitStopFailure()?.let(throwable::addSuppressed)
                            throw throwable
                        }
                    runningRuntime = result.runtime
                }

                is ProxyServerRuntimeResult.StartupFailed -> {
                    throw ProxyServerForegroundRuntimeStartException(
                        startupError = result.startup.startupError,
                        message = "Proxy runtime startup failed: ${result.startup.startupError}",
                    )
                }

                is ProxyServerRuntimeResult.AcceptLoopLaunchFailed -> {
                    throw ProxyServerForegroundRuntimeStartException(
                        startupError = null,
                        message = "Proxy runtime accept loop launch failed",
                        cause = result.exception,
                    )
                }
            }
        }
    }

    override fun stopProxyRuntime() {
        val stopRequest =
            synchronized(lock) {
                check(!closed) { "Proxy foreground runtime lifecycle is closed" }
                if (runningRuntime == null || stopInProgress) {
                    return
                }
                val latch = CountDownLatch(1)
                stopCompletionLatch = latch
                stopInProgress = true
                lastStopFailure = null
                lastStopWorkerFailure = null
                StopRequest(runtime = runningRuntime!!, latch = latch)
            }

        stopRequest.runtime.requestStop()
        try {
            stopExecutor.execute {
                completeStopRequest(
                    stopRequest = stopRequest,
                    failure =
                        try {
                            stopRequest.runtime.awaitStopFailure()
                        } catch (throwable: Throwable) {
                            ProxyServerForegroundRuntimeStopWorkerException(throwable)
                        },
                )
            }
        } catch (exception: RejectedExecutionException) {
            completeStopRequest(
                stopRequest = stopRequest,
                failure = ProxyServerForegroundRuntimeStopWorkerException(exception),
            )
        }
    }

    companion object {
        internal fun forTesting(
            startRuntime: () -> ProxyServerRuntimeResult,
            stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
            stopExecutor: java.util.concurrent.Executor,
        ): ProxyServerForegroundRuntimeLifecycle =
            ProxyServerForegroundRuntimeLifecycle(
                startRuntime = startRuntime,
                stopTimeoutMillis = stopTimeoutMillis,
                stopExecutor = stopExecutor,
            )
    }

    private fun completeStopRequest(
        stopRequest: StopRequest,
        failure: RuntimeException?,
    ) {
        var registrationToClose: Closeable? = null
        try {
            synchronized(lock) {
                if (runningRuntime === stopRequest.runtime) {
                    when (failure) {
                        null -> {
                            runningRuntime = null
                            registrationToClose = runningRuntimeRegistration
                            runningRuntimeRegistration = null
                        }
                        is ProxyServerForegroundRuntimeStopException -> {
                            lastStopFailure = failure.stopResult
                            if (failure.stopResult.isTerminal()) {
                                runningRuntime = null
                                registrationToClose = runningRuntimeRegistration
                                runningRuntimeRegistration = null
                            }
                        }
                        else -> lastStopWorkerFailure = failure
                    }
                    stopInProgress = false
                    if (stopCompletionLatch === stopRequest.latch) {
                        stopCompletionLatch = null
                    }
                }
            }
            registrationToClose?.close()
        } catch (throwable: Throwable) {
            synchronized(lock) {
                lastStopWorkerFailure = ProxyServerForegroundRuntimeStopWorkerException(throwable)
            }
        } finally {
            stopRequest.latch.countDown()
        }
    }

    override fun close() {
        val runtime =
            synchronized(lock) {
                if (closed) {
                    return
                }
                runningRuntime
            }

        var failure: Throwable? = null
        try {
            if (runtime != null) {
                runtime.requestStop()
                runtime.awaitStopFailure()?.let { throw it }
            }
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            val registrationToClose =
                synchronized(lock) {
                    runningRuntimeRegistration.also {
                        runningRuntimeRegistration = null
                    }
                }
            try {
                registrationToClose?.close()
            } catch (throwable: Throwable) {
                failure = failure ?: throwable
            }
            synchronized(lock) {
                if (runningRuntime === runtime) {
                    runningRuntime = null
                }
                stopInProgress = false
                stopCompletionLatch?.countDown()
                stopCompletionLatch = null
                closed = true
            }
            (stopExecutor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }

        failure?.let { throw it }
    }

    internal fun awaitPendingStopForTesting(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        val latch = synchronized(lock) { stopCompletionLatch } ?: return true
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun RunningProxyServerRuntime.awaitStopFailure(): ProxyServerForegroundRuntimeStopException? =
        when (val result = awaitStopped(stopTimeoutMillis)) {
            is ProxyServerRuntimeStopResult.Finished ->
                when (result.result) {
                    is ProxyBoundServerAcceptLoopResult.Stopped -> null
                    is ProxyBoundServerAcceptLoopResult.Failed ->
                        ProxyServerForegroundRuntimeStopException(result)
                }
            ProxyServerRuntimeStopResult.Interrupted,
            is ProxyServerRuntimeStopResult.Failed,
            ProxyServerRuntimeStopResult.TimedOut,
            -> ProxyServerForegroundRuntimeStopException(result)
        }
}

private fun ProxyServerRuntimeStopResult.isTerminal(): Boolean =
    when (this) {
        is ProxyServerRuntimeStopResult.Finished,
        is ProxyServerRuntimeStopResult.Failed,
        -> true
        ProxyServerRuntimeStopResult.Interrupted,
        ProxyServerRuntimeStopResult.TimedOut,
        -> false
    }

private data class StopRequest(
    val runtime: RunningProxyServerRuntime,
    val latch: CountDownLatch,
)

private object ProxyRuntimeStopThreadFactory : ThreadFactory {
    private val threadIds = AtomicLong(0)

    override fun newThread(command: Runnable): Thread = Thread(command, "CellularProxyRuntimeStop-${threadIds.incrementAndGet()}")
}

private const val DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L
