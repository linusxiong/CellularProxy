package com.cellularproxy.app.service

import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ExecutorForegroundProxyRuntimeLifecycleTest {
    @Test
    fun `start command is enqueued on owned single worker without running runtime work on caller thread`() {
        val delegate = RecordingForegroundProxyRuntimeLifecycle()
        val executor = QueuedExecutor()
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.forTesting(
            delegate = delegate,
            executor = executor,
        )

        lifecycle.startProxyRuntime()

        assertEquals(emptyList(), delegate.events)
        assertEquals(1, executor.queuedTaskCount)

        executor.runNext()

        assertEquals(listOf("start"), delegate.events)
    }

    @Test
    fun `stop command is enqueued on owned single worker without running runtime work on caller thread`() {
        val delegate = RecordingForegroundProxyRuntimeLifecycle()
        val executor = QueuedExecutor()
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.forTesting(
            delegate = delegate,
            executor = executor,
        )

        lifecycle.stopProxyRuntime()

        assertEquals(emptyList(), delegate.events)
        assertEquals(1, executor.queuedTaskCount)

        executor.runNext()

        assertEquals(listOf("stop"), delegate.events)
    }

    @Test
    fun `public factory runs runtime work on a background worker thread`() {
        val callerThread = Thread.currentThread()
        val startThread = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.create(
            delegate = object : ForegroundProxyRuntimeLifecycle {
                override fun startProxyRuntime() {
                    startThread.set(Thread.currentThread())
                    started.countDown()
                }

                override fun stopProxyRuntime() = Unit
            },
        )

        try {
            lifecycle.startProxyRuntime()

            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNotSame(callerThread, startThread.get())
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `public constructors do not expose generic executor injection`() {
        val publicConstructorParameterLists = ExecutorForegroundProxyRuntimeLifecycle::class.java
            .constructors
            .map { constructor -> constructor.parameterTypes.toList() }

        assertTrue(
            publicConstructorParameterLists.none { parameters ->
                parameters == listOf(
                    ForegroundProxyRuntimeLifecycle::class.java,
                    Executor::class.java,
                )
            },
        )
    }

    @Test
    fun `executor rejection propagates so foreground service can clean up failed starts`() {
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.forTesting(
            delegate = RecordingForegroundProxyRuntimeLifecycle(),
            executor = RejectingExecutor,
        )

        val failure = assertFailsWith<RejectedExecutionException> {
            lifecycle.startProxyRuntime()
        }

        assertEquals("runtime executor rejected command", failure.message)
    }

    @Test
    fun `queued runtime exception belongs to runtime task and not command caller`() {
        val delegate = RecordingForegroundProxyRuntimeLifecycle(
            startException = IllegalStateException("start failed later"),
        )
        val executor = QueuedExecutor()
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.forTesting(
            delegate = delegate,
            executor = executor,
        )

        lifecycle.startProxyRuntime()

        val failure = assertFailsWith<IllegalStateException> {
            executor.runNext()
        }

        assertEquals("start failed later", failure.message)
        assertTrue(delegate.events.contains("start"))
    }

    @Test
    fun `closing lifecycle closes closeable delegate after stopping owned worker`() {
        val delegate = CloseableRecordingForegroundProxyRuntimeLifecycle()
        val lifecycle = ExecutorForegroundProxyRuntimeLifecycle.forTesting(
            delegate = delegate,
            executor = QueuedExecutor(),
        )

        lifecycle.close()
        lifecycle.close()

        assertEquals(1, delegate.closeCount)
    }
}

private class RecordingForegroundProxyRuntimeLifecycle(
    val events: MutableList<String> = mutableListOf(),
    private val startException: Exception? = null,
    private val stopException: Exception? = null,
) : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime() {
        events += "start"
        startException?.let { throw it }
    }

    override fun stopProxyRuntime() {
        events += "stop"
        stopException?.let { throw it }
    }
}

private class CloseableRecordingForegroundProxyRuntimeLifecycle :
    ForegroundProxyRuntimeLifecycle,
    Closeable {
    var closeCount: Int = 0
        private set

    override fun startProxyRuntime() = Unit

    override fun stopProxyRuntime() = Unit

    override fun close() {
        closeCount += 1
    }
}

private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val queuedTaskCount: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks += command
    }

    fun runNext() {
        tasks.removeFirst().run()
    }
}

private object RejectingExecutor : Executor {
    override fun execute(command: Runnable) {
        throw RejectedExecutionException("runtime executor rejected command")
    }
}
