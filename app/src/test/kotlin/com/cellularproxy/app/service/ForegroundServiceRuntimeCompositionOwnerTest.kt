package com.cellularproxy.app.service

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForegroundServiceRuntimeCompositionOwnerTest {
    @AfterTest
    fun resetRegistry() {
        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
    }

    @Test
    fun `installIfNeeded installs once until owner is closed`() {
        val installations = mutableListOf<RecordingCloseable>()
        val owner = ForegroundServiceRuntimeCompositionOwner {
            RecordingCloseable().also(installations::add)
        }

        val first = owner.installIfNeeded()
        val second = owner.installIfNeeded()

        assertSame(first, second)
        assertEquals(1, installations.size)

        owner.close()
        owner.close()

        assertEquals(1, installations.single().closeCount)

        val third = owner.installIfNeeded()

        assertEquals(2, installations.size)
        assertSame(installations.last(), third)
    }

    @Test
    fun `startProxyRuntime installs composition after foreground promotion and delegates to installed lifecycle`() {
        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
        val events = mutableListOf<String>()
        val started = CountDownLatch(1)
        val registration = RecordingCloseable()
        val installedLifecycle = OwnerRecordingRuntimeLifecycle(events, started)
        val owner = ForegroundServiceRuntimeCompositionOwner {
            events += "owner:install"
            ForegroundProxyRuntimeLifecycleInstaller.install(installedLifecycle).let {
                Closeable {
                    it.close()
                    registration.close()
                }
            }
        }

        try {
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY),
                runtimeLifecycle = owner,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

            assertEquals("service:${ForegroundServiceCommandEffect.PromoteToForeground}", events[0])
            assertEquals("owner:install", events[1])
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue("runtime:start" in events)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `closeQuietly swallows cleanup failures and clears current installation`() {
        val owner = ForegroundServiceRuntimeCompositionOwner {
            Closeable {
                throw IllegalStateException("close failed")
            }
        }

        owner.installIfNeeded()

        owner.closeQuietly()

        val replacement = owner.installIfNeeded()
        assertFailsWith<IllegalStateException> {
            replacement.close()
        }
    }
}

private class RecordingCloseable : Closeable {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
    }
}

private class OwnerRecordingRuntimeLifecycle(
    private val events: MutableList<String>,
    private val started: CountDownLatch,
) : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime() {
        events += "runtime:start"
        started.countDown()
    }

    override fun stopProxyRuntime() {
        events += "runtime:stop"
    }
}
