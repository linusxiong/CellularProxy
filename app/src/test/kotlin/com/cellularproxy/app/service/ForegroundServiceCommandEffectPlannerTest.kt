package com.cellularproxy.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForegroundServiceCommandEffectPlannerTest {
    @Test
    fun `accepted start command plans proxy runtime start and foreground promotion`() {
        val result = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY)

        assertEquals(
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.StartProxyRuntime,
                serviceEffect = ForegroundServiceCommandEffect.PromoteToForeground,
            ),
            ForegroundServiceCommandEffectPlanner.plan(result),
        )
    }

    @Test
    fun `accepted stop commands plan proxy runtime stop and foreground service stop`() {
        listOf(
            ForegroundServiceActions.STOP_PROXY,
            ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION,
        ).forEach { action ->
            assertEquals(
                ForegroundServiceCommandEffectPlan(
                    runtimeEffect = ForegroundServiceRuntimeEffect.StopProxyRuntime,
                    serviceEffect = ForegroundServiceCommandEffect.StopForegroundAndSelf,
                ),
                ForegroundServiceCommandEffectPlanner.plan(ForegroundServiceCommandParser.parse(action)),
            )
        }
    }

    @Test
    fun `ignored commands stop the stray service instance`() {
        assertEquals(
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.None,
                serviceEffect = ForegroundServiceCommandEffect.StopSelf,
            ),
            ForegroundServiceCommandEffectPlanner.plan(ForegroundServiceCommandParser.parse(null)),
        )
    }

    @Test
    fun `effect plan rejects contradictory runtime and service effects`() {
        assertFailsWith<IllegalArgumentException> {
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.StartProxyRuntime,
                serviceEffect = ForegroundServiceCommandEffect.StopSelf,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.StopProxyRuntime,
                serviceEffect = ForegroundServiceCommandEffect.PromoteToForeground,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundServiceCommandEffectPlan(
                runtimeEffect = ForegroundServiceRuntimeEffect.None,
                serviceEffect = ForegroundServiceCommandEffect.PromoteToForeground,
            )
        }
    }
}
