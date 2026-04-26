package com.cellularproxy.app.service

/**
 * Implementations must return quickly and move blocking startup or shutdown
 * work off the Android service callback thread.
 */
interface ForegroundProxyRuntimeLifecycle {
    fun startProxyRuntime()

    fun stopProxyRuntime()
}

sealed interface ForegroundProxyRuntimeActionResult {
    data object Started : ForegroundProxyRuntimeActionResult
    data object Stopped : ForegroundProxyRuntimeActionResult
    data object NotRequested : ForegroundProxyRuntimeActionResult

    data class Failed(
        val exception: Exception,
    ) : ForegroundProxyRuntimeActionResult
}

data class ForegroundServiceCommandExecution(
    val plan: ForegroundServiceCommandEffectPlan,
    val runtimeActionResult: ForegroundProxyRuntimeActionResult,
) {
    init {
        val valid = when (plan.runtimeEffect) {
            ForegroundServiceRuntimeEffect.StartProxyRuntime ->
                runtimeActionResult == ForegroundProxyRuntimeActionResult.Started ||
                    runtimeActionResult is ForegroundProxyRuntimeActionResult.Failed
            ForegroundServiceRuntimeEffect.StopProxyRuntime ->
                runtimeActionResult == ForegroundProxyRuntimeActionResult.Stopped ||
                    runtimeActionResult is ForegroundProxyRuntimeActionResult.Failed
            ForegroundServiceRuntimeEffect.None ->
                runtimeActionResult == ForegroundProxyRuntimeActionResult.NotRequested
        }
        require(valid) {
            "Foreground service execution result must match the planned runtime effect"
        }
    }
}

object ForegroundServiceCommandExecutor {
    fun execute(
        commandResult: ForegroundServiceCommandResult,
        runtimeLifecycle: ForegroundProxyRuntimeLifecycle,
        applyServiceEffect: (ForegroundServiceCommandEffect) -> Unit,
    ): ForegroundServiceCommandExecution {
        val plan = ForegroundServiceCommandEffectPlanner.plan(commandResult)
        val runtimeResult = when (plan.runtimeEffect) {
            ForegroundServiceRuntimeEffect.StartProxyRuntime -> {
                applyServiceEffect(plan.serviceEffect)
                val startResult = invokeRuntimeAction { runtimeLifecycle.startProxyRuntime() }
                    .startedOrFailed()
                if (startResult is ForegroundProxyRuntimeActionResult.Failed) {
                    applyServiceEffect(ForegroundServiceCommandEffect.StopForegroundAndSelf)
                }
                startResult
            }

            ForegroundServiceRuntimeEffect.StopProxyRuntime -> {
                val stopResult = invokeRuntimeAction { runtimeLifecycle.stopProxyRuntime() }
                    .stoppedOrFailed()
                applyServiceEffect(plan.serviceEffect)
                stopResult
            }

            ForegroundServiceRuntimeEffect.None -> {
                applyServiceEffect(plan.serviceEffect)
                ForegroundProxyRuntimeActionResult.NotRequested
            }
        }

        return ForegroundServiceCommandExecution(
            plan = plan,
            runtimeActionResult = runtimeResult,
        )
    }

    private inline fun invokeRuntimeAction(action: () -> Unit): RuntimeActionInvocationResult =
        try {
            action()
            RuntimeActionInvocationResult.Completed
        } catch (exception: Exception) {
            RuntimeActionInvocationResult.Failed(exception)
        }
}

private sealed interface RuntimeActionInvocationResult {
    data object Completed : RuntimeActionInvocationResult

    data class Failed(
        val exception: Exception,
    ) : RuntimeActionInvocationResult
}

private fun RuntimeActionInvocationResult.startedOrFailed(): ForegroundProxyRuntimeActionResult =
    when (this) {
        RuntimeActionInvocationResult.Completed -> ForegroundProxyRuntimeActionResult.Started
        is RuntimeActionInvocationResult.Failed -> ForegroundProxyRuntimeActionResult.Failed(exception)
    }

private fun RuntimeActionInvocationResult.stoppedOrFailed(): ForegroundProxyRuntimeActionResult =
    when (this) {
        RuntimeActionInvocationResult.Completed -> ForegroundProxyRuntimeActionResult.Stopped
        is RuntimeActionInvocationResult.Failed -> ForegroundProxyRuntimeActionResult.Failed(exception)
    }

object UninstalledForegroundProxyRuntimeLifecycle : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime(): Nothing =
        throw IllegalStateException("Foreground proxy runtime lifecycle is not installed")

    override fun stopProxyRuntime(): Nothing =
        throw IllegalStateException("Foreground proxy runtime lifecycle is not installed")
}
