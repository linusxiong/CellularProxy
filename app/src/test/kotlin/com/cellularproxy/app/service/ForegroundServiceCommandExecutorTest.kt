package com.cellularproxy.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ForegroundServiceCommandExecutorTest {
    @Test
    fun `start command promotes foreground before starting proxy runtime`() {
        val events = mutableListOf<String>()
        val lifecycle = RecordingRuntimeLifecycle(events)

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY),
                runtimeLifecycle = lifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(
            listOf(
                "service:${ForegroundServiceCommandEffect.PromoteToForeground}",
                "runtime:start",
            ),
            events,
        )
        assertEquals(
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.StartProxyRuntime,
                serviceEffect = ForegroundServiceCommandEffect.PromoteToForeground,
            ),
            execution.plan,
        )
        assertEquals(ForegroundProxyRuntimeActionResult.Started, execution.runtimeActionResult)
    }

    @Test
    fun `stop command stops proxy runtime before stopping foreground service`() {
        val events = mutableListOf<String>()
        val lifecycle = RecordingRuntimeLifecycle(events)

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.STOP_PROXY),
                runtimeLifecycle = lifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(
            listOf(
                "runtime:stop",
                "service:${ForegroundServiceCommandEffect.StopForegroundAndSelf}",
            ),
            events,
        )
        assertEquals(
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.StopProxyRuntime,
                serviceEffect = ForegroundServiceCommandEffect.StopForegroundAndSelf,
            ),
            execution.plan,
        )
        assertEquals(ForegroundProxyRuntimeActionResult.Stopped, execution.runtimeActionResult)
    }

    @Test
    fun `ignored command stops stray service without touching proxy runtime`() {
        val events = mutableListOf<String>()
        val lifecycle = RecordingRuntimeLifecycle(events)

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(null),
                runtimeLifecycle = lifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(listOf("service:${ForegroundServiceCommandEffect.StopSelf}"), events)
        assertEquals(
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.None,
                serviceEffect = ForegroundServiceCommandEffect.StopSelf,
            ),
            execution.plan,
        )
        assertEquals(ForegroundProxyRuntimeActionResult.NotRequested, execution.runtimeActionResult)
    }

    @Test
    fun `start runtime failure is captured and foreground service is stopped`() {
        val events = mutableListOf<String>()
        val exception = IllegalStateException("runtime unavailable")
        val lifecycle = RecordingRuntimeLifecycle(events, startException = exception)

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY),
                runtimeLifecycle = lifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(
            listOf(
                "service:${ForegroundServiceCommandEffect.PromoteToForeground}",
                "runtime:start",
                "service:${ForegroundServiceCommandEffect.StopForegroundAndSelf}",
            ),
            events,
        )
        assertSame(
            exception,
            assertIs<ForegroundProxyRuntimeActionResult.Failed>(execution.runtimeActionResult).exception,
        )
    }

    @Test
    fun `stop runtime failure is captured while foreground service still stops`() {
        val events = mutableListOf<String>()
        val exception = IllegalStateException("stop failed")
        val lifecycle = RecordingRuntimeLifecycle(events, stopException = exception)

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION),
                runtimeLifecycle = lifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(
            listOf(
                "runtime:stop",
                "service:${ForegroundServiceCommandEffect.StopForegroundAndSelf}",
            ),
            events,
        )
        assertSame(
            exception,
            assertIs<ForegroundProxyRuntimeActionResult.Failed>(execution.runtimeActionResult).exception,
        )
    }

    @Test
    fun `fatal runtime errors are rethrown`() {
        val fatal = OutOfMemoryError("fatal")
        val lifecycle =
            object : ForegroundProxyRuntimeLifecycle {
                override fun startProxyRuntime(): Unit = throw fatal

                override fun stopProxyRuntime() = Unit
            }

        assertSame(
            fatal,
            assertFailsWith<OutOfMemoryError> {
                ForegroundServiceCommandExecutor.execute(
                    commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY),
                    runtimeLifecycle = lifecycle,
                    applyServiceEffect = {},
                )
            },
        )
    }

    @Test
    fun `default uninstalled runtime lifecycle fails instead of reporting fake runtime success`() {
        val events = mutableListOf<String>()

        val execution =
            ForegroundServiceCommandExecutor.execute(
                commandResult = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY),
                runtimeLifecycle = UninstalledForegroundProxyRuntimeLifecycle,
                applyServiceEffect = { effect -> events += "service:$effect" },
            )

        assertEquals(
            listOf(
                "service:${ForegroundServiceCommandEffect.PromoteToForeground}",
                "service:${ForegroundServiceCommandEffect.StopForegroundAndSelf}",
            ),
            events,
        )
        assertIs<ForegroundProxyRuntimeActionResult.Failed>(execution.runtimeActionResult)
    }
}

private class RecordingRuntimeLifecycle(
    private val events: MutableList<String>,
    private val startException: Exception? = null,
    private val stopException: Exception? = null,
) : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime() {
        events += "runtime:start"
        startException?.let { throw it }
    }

    override fun stopProxyRuntime() {
        events += "runtime:stop"
        stopException?.let { throw it }
    }
}
