package com.cellularproxy.app.service

import java.io.Closeable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ForegroundProxyRuntimeLifecycleRegistryTest {
    @AfterTest
    fun resetRegistry() {
        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
    }

    @Test
    fun `installed lifecycle is exposed until its registration is closed`() {
        val lifecycle = RecordingRegistryRuntimeLifecycle()

        val registration = ForegroundProxyRuntimeLifecycleRegistry.install(lifecycle)

        assertSame(lifecycle, ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle)

        registration.close()

        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
    }

    @Test
    fun `closing stale registration does not uninstall replacement lifecycle`() {
        val first = RecordingRegistryRuntimeLifecycle()
        val second = RecordingRegistryRuntimeLifecycle()
        val firstRegistration = ForegroundProxyRuntimeLifecycleRegistry.install(first)
        val secondRegistration = ForegroundProxyRuntimeLifecycleRegistry.install(second)

        firstRegistration.close()

        assertSame(second, ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle)

        secondRegistration.close()

        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
    }

    @Test
    fun `installing replacement closes previous closeable lifecycle once`() {
        val first = CloseableRegistryRuntimeLifecycle()
        val second = CloseableRegistryRuntimeLifecycle()
        val firstRegistration = ForegroundProxyRuntimeLifecycleRegistry.install(first)
        val secondRegistration = ForegroundProxyRuntimeLifecycleRegistry.install(second)

        assertEquals(1, first.closeCount)
        assertSame(second, ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle)

        firstRegistration.close()
        secondRegistration.close()

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun `install returns replacement registration even when previous lifecycle close later fails`() {
        val first = ThrowingCloseRegistryRuntimeLifecycle()
        val second = CloseableRegistryRuntimeLifecycle()
        ForegroundProxyRuntimeLifecycleRegistry.install(first)

        val secondRegistration = ForegroundProxyRuntimeLifecycleRegistry.install(second)

        assertSame(second, ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle)
        assertEquals(1, first.closeCount)

        secondRegistration.close()

        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
        assertEquals(1, second.closeCount)

        val failure = assertFailsWith<IllegalStateException> {
            ForegroundProxyRuntimeLifecycleRegistry.throwPendingCloseFailures()
        }
        assertEquals("close failed", failure.message)
    }

    @Test
    fun `closing registration closes closeable installed lifecycle once`() {
        val lifecycle = CloseableRegistryRuntimeLifecycle()
        val registration = ForegroundProxyRuntimeLifecycleRegistry.install(lifecycle)

        registration.close()
        registration.close()

        assertEquals(1, lifecycle.closeCount)
    }

    @Test
    fun `test reset closes current closeable lifecycle`() {
        val lifecycle = CloseableRegistryRuntimeLifecycle()
        ForegroundProxyRuntimeLifecycleRegistry.install(lifecycle)

        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()

        assertSame(
            UninstalledForegroundProxyRuntimeLifecycle,
            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
        )
        assertEquals(1, lifecycle.closeCount)
    }
}

private open class RecordingRegistryRuntimeLifecycle : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime() = Unit

    override fun stopProxyRuntime() = Unit
}

private class CloseableRegistryRuntimeLifecycle : RecordingRegistryRuntimeLifecycle(), Closeable {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
    }
}

private class ThrowingCloseRegistryRuntimeLifecycle : RecordingRegistryRuntimeLifecycle(), Closeable {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
        throw IllegalStateException("close failed")
    }
}
