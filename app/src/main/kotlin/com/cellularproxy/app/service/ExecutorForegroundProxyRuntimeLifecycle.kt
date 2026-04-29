package com.cellularproxy.app.service

import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ExecutorForegroundProxyRuntimeLifecycle private constructor(
    private val delegate: ForegroundProxyRuntimeLifecycle,
    private val executor: Executor,
    private val closeAction: () -> Unit,
) : ForegroundProxyRuntimeLifecycle,
    Closeable {
    private val closed = AtomicBoolean(false)

    companion object {
        fun create(
            delegate: ForegroundProxyRuntimeLifecycle,
            threadFactory: ThreadFactory = ForegroundRuntimeThreadFactory,
        ): ExecutorForegroundProxyRuntimeLifecycle {
            val executor = Executors.newSingleThreadExecutor(threadFactory)
            return ExecutorForegroundProxyRuntimeLifecycle(
                delegate = delegate,
                executor = executor,
                closeAction = { executor.shutdownNow() },
            )
        }

        internal fun forTesting(
            delegate: ForegroundProxyRuntimeLifecycle,
            executor: Executor,
        ): ExecutorForegroundProxyRuntimeLifecycle = ExecutorForegroundProxyRuntimeLifecycle(
            delegate = delegate,
            executor = executor,
            closeAction = {},
        )
    }

    override fun startProxyRuntime() {
        executor.execute {
            delegate.startProxyRuntime()
        }
    }

    override fun stopProxyRuntime() {
        executor.execute {
            delegate.stopProxyRuntime()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        var failure: Throwable? = null

        try {
            closeAction()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        try {
            (delegate as? Closeable)?.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run {
                failure = throwable
            }
        }

        failure?.let { throw it }
    }
}

private object ForegroundRuntimeThreadFactory : ThreadFactory {
    private val threadIds = AtomicLong(0)

    override fun newThread(command: Runnable): Thread = Thread(command, "CellularProxyRuntimeLifecycle-${threadIds.incrementAndGet()}")
}
