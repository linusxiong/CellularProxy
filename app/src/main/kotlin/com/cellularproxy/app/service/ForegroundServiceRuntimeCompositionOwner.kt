package com.cellularproxy.app.service

import java.io.Closeable

internal class ForegroundServiceRuntimeCompositionOwner(
    private val install: () -> Closeable,
) : ForegroundProxyRuntimeLifecycle,
    Closeable {
    private var installation: Closeable? = null

    @Synchronized
    fun installIfNeeded(): Closeable {
        installation?.let { return it }

        return install().also { installation = it }
    }

    override fun startProxyRuntime() {
        installIfNeeded()
        ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle.startProxyRuntime()
    }

    override fun stopProxyRuntime() {
        ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle.stopProxyRuntime()
    }

    fun closeQuietly() {
        try {
            close()
        } catch (throwable: Throwable) {
            if (throwable.isFatal()) {
                throw throwable
            }
        }
    }

    @Synchronized
    override fun close() {
        val installationToClose = installation ?: return
        installation = null
        installationToClose.close()
    }
}

private fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath || this is LinkageError
