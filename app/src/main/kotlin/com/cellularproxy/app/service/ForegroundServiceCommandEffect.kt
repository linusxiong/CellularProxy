package com.cellularproxy.app.service

sealed interface ForegroundServiceCommandEffect {
    data object PromoteToForeground : ForegroundServiceCommandEffect

    data object StopForegroundAndSelf : ForegroundServiceCommandEffect

    data object StopSelf : ForegroundServiceCommandEffect
}

enum class ForegroundServiceRuntimeEffect {
    StartProxyRuntime,
    StopProxyRuntime,
    None,
}

data class ForegroundServiceCommandEffectPlan(
    val runtimeEffect: ForegroundServiceRuntimeEffect,
    val serviceEffect: ForegroundServiceCommandEffect,
) {
    init {
        val valid =
            when (runtimeEffect) {
                ForegroundServiceRuntimeEffect.StartProxyRuntime ->
                    serviceEffect == ForegroundServiceCommandEffect.PromoteToForeground
                ForegroundServiceRuntimeEffect.StopProxyRuntime ->
                    serviceEffect == ForegroundServiceCommandEffect.StopForegroundAndSelf
                ForegroundServiceRuntimeEffect.None ->
                    serviceEffect == ForegroundServiceCommandEffect.StopSelf
            }
        require(valid) {
            "Foreground service runtime and service effects must describe the same command outcome"
        }
    }
}

object ForegroundServiceCommandEffectPlanner {
    fun plan(result: ForegroundServiceCommandResult): ForegroundServiceCommandEffectPlan =
        when (result) {
            ForegroundServiceCommandResult.Ignored ->
                ForegroundServiceCommandEffectPlan(
                    runtimeEffect = ForegroundServiceRuntimeEffect.None,
                    serviceEffect = ForegroundServiceCommandEffect.StopSelf,
                )
            is ForegroundServiceCommandResult.Accepted ->
                when (result.decision.command) {
                    ForegroundServiceCommand.Start ->
                        ForegroundServiceCommandEffectPlan(
                            runtimeEffect = ForegroundServiceRuntimeEffect.StartProxyRuntime,
                            serviceEffect = ForegroundServiceCommandEffect.PromoteToForeground,
                        )
                    ForegroundServiceCommand.Stop ->
                        ForegroundServiceCommandEffectPlan(
                            runtimeEffect = ForegroundServiceRuntimeEffect.StopProxyRuntime,
                            serviceEffect = ForegroundServiceCommandEffect.StopForegroundAndSelf,
                        )
                }
        }
}
