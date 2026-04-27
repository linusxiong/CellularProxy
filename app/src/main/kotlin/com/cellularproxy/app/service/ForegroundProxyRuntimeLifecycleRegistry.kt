package com.cellularproxy.app.service

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal object ForegroundProxyRuntimeLifecycleRegistry {
    private val lock = Any()
    private val closeFailures = mutableListOf<Throwable>()
    private var currentEntry: InstalledForegroundProxyRuntimeLifecycleEntry =
        InstalledForegroundProxyRuntimeLifecycleEntry(UninstalledForegroundProxyRuntimeLifecycle)

    @Volatile
    var foregroundProxyRuntimeLifecycle: ForegroundProxyRuntimeLifecycle = currentEntry.lifecycle
        private set

    fun install(lifecycle: ForegroundProxyRuntimeLifecycle): Closeable {
        val newEntry = InstalledForegroundProxyRuntimeLifecycleEntry(lifecycle)
        val previous =
            synchronized(lock) {
                val previousEntry = currentEntry
                currentEntry = newEntry
                foregroundProxyRuntimeLifecycle = lifecycle
                previousEntry
            }
        previous.closeAndRecordFailure()
        return InstalledForegroundProxyRuntimeLifecycleRegistration(newEntry)
    }

    fun resetForTesting() {
        val previous =
            synchronized(lock) {
                val previousEntry = currentEntry
                currentEntry = InstalledForegroundProxyRuntimeLifecycleEntry(UninstalledForegroundProxyRuntimeLifecycle)
                foregroundProxyRuntimeLifecycle = UninstalledForegroundProxyRuntimeLifecycle
                closeFailures.clear()
                previousEntry
            }
        previous.closeAndRecordFailure()
    }

    fun throwPendingCloseFailures() {
        val failure =
            synchronized(lock) {
                val firstFailure = closeFailures.firstOrNull() ?: return
                closeFailures.clear()
                firstFailure
            }
        throw failure
    }

    internal fun recordCloseFailure(throwable: Throwable) {
        synchronized(lock) {
            closeFailures += throwable
        }
    }

    private class InstalledForegroundProxyRuntimeLifecycleRegistration(
        private val entry: InstalledForegroundProxyRuntimeLifecycleEntry,
    ) : Closeable {
        override fun close() {
            synchronized(lock) {
                if (currentEntry === entry) {
                    currentEntry = InstalledForegroundProxyRuntimeLifecycleEntry(UninstalledForegroundProxyRuntimeLifecycle)
                    foregroundProxyRuntimeLifecycle = UninstalledForegroundProxyRuntimeLifecycle
                }
            }

            entry.close()
        }
    }
}

object ForegroundProxyRuntimeLifecycleInstaller {
    fun install(delegate: ForegroundProxyRuntimeLifecycle): Closeable {
        val lifecycle =
            when (delegate) {
                is SynchronousForegroundProxyRuntimeLifecycle -> delegate
                else -> ExecutorForegroundProxyRuntimeLifecycle.create(delegate)
            }
        return ForegroundProxyRuntimeLifecycleRegistry.install(lifecycle)
    }
}

private class InstalledForegroundProxyRuntimeLifecycleEntry(
    val lifecycle: ForegroundProxyRuntimeLifecycle,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            (lifecycle as? Closeable)?.close()
        }
    }
}

private fun InstalledForegroundProxyRuntimeLifecycleEntry.closeAndRecordFailure() {
    try {
        close()
    } catch (throwable: Throwable) {
        ForegroundProxyRuntimeLifecycleRegistry.recordCloseFailure(throwable)
    }
}
