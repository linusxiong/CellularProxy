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
        val valid =
            when (plan.runtimeEffect) {
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

enum class ForegroundServiceAuditOutcome {
    RuntimeStarted,
    RuntimeStopped,
    RuntimeFailed,
}

data class ForegroundServiceAuditRecord(
    val occurredAtEpochMillis: Long,
    val event: ForegroundServiceAuditEvent,
    val command: ForegroundServiceCommand,
    val source: ForegroundServiceCommandSource,
    val outcome: ForegroundServiceAuditOutcome,
) {
    init {
        require(occurredAtEpochMillis >= 0) { "Foreground service audit timestamp must be non-negative" }
        when (event) {
            ForegroundServiceAuditEvent.StartRequested -> {
                require(command == ForegroundServiceCommand.Start) {
                    "Start foreground service audit events require a start command"
                }
                require(source == ForegroundServiceCommandSource.App) {
                    "Start foreground service audit events require the app source"
                }
                require(outcome != ForegroundServiceAuditOutcome.RuntimeStopped) {
                    "Start foreground service audit events cannot report runtime stopped"
                }
            }
            ForegroundServiceAuditEvent.StopRequested -> {
                require(command == ForegroundServiceCommand.Stop) {
                    "Stop foreground service audit events require a stop command"
                }
                require(source == ForegroundServiceCommandSource.App) {
                    "Stop foreground service audit events require the app source"
                }
                require(outcome != ForegroundServiceAuditOutcome.RuntimeStarted) {
                    "Stop foreground service audit events cannot report runtime started"
                }
            }
            ForegroundServiceAuditEvent.NotificationStopRequested -> {
                require(command == ForegroundServiceCommand.Stop) {
                    "Notification-stop foreground service audit events require a stop command"
                }
                require(source == ForegroundServiceCommandSource.Notification) {
                    "Notification-stop foreground service audit events require the notification source"
                }
                require(outcome != ForegroundServiceAuditOutcome.RuntimeStarted) {
                    "Notification-stop foreground service audit events cannot report runtime started"
                }
            }
        }
    }
}

object ForegroundServiceCommandExecutor {
    fun execute(
        commandResult: ForegroundServiceCommandResult,
        runtimeLifecycle: ForegroundProxyRuntimeLifecycle,
        applyServiceEffect: (ForegroundServiceCommandEffect) -> Unit,
        clock: () -> Long = System::currentTimeMillis,
        recordAudit: (ForegroundServiceAuditRecord) -> Unit = {},
        reportAuditFailure: (Exception) -> Unit = {},
    ): ForegroundServiceCommandExecution {
        val plan = ForegroundServiceCommandEffectPlanner.plan(commandResult)
        val runtimeResult =
            when (plan.runtimeEffect) {
                ForegroundServiceRuntimeEffect.StartProxyRuntime -> {
                    applyServiceEffect(plan.serviceEffect)
                    val startResult =
                        invokeRuntimeAction { runtimeLifecycle.startProxyRuntime() }
                            .startedOrFailed()
                    if (startResult is ForegroundProxyRuntimeActionResult.Failed) {
                        applyServiceEffect(ForegroundServiceCommandEffect.StopForegroundAndSelf)
                    }
                    startResult
                }

                ForegroundServiceRuntimeEffect.StopProxyRuntime -> {
                    val stopResult =
                        invokeRuntimeAction { runtimeLifecycle.stopProxyRuntime() }
                            .stoppedOrFailed()
                    applyServiceEffect(plan.serviceEffect)
                    stopResult
                }

                ForegroundServiceRuntimeEffect.None -> {
                    applyServiceEffect(plan.serviceEffect)
                    ForegroundProxyRuntimeActionResult.NotRequested
                }
            }

        if (commandResult is ForegroundServiceCommandResult.Accepted) {
            val auditRecord =
                commandResult.decision.toAuditRecord(
                    occurredAtEpochMillis = clock(),
                    runtimeActionResult = runtimeResult,
                )
            try {
                recordAudit(auditRecord)
            } catch (exception: Exception) {
                reportAuditFailure(exception)
            }
        }

        return ForegroundServiceCommandExecution(
            plan = plan,
            runtimeActionResult = runtimeResult,
        )
    }

    private inline fun invokeRuntimeAction(action: () -> Unit): RuntimeActionInvocationResult = try {
        action()
        RuntimeActionInvocationResult.Completed
    } catch (exception: Exception) {
        RuntimeActionInvocationResult.Failed(exception)
    }
}

private fun ForegroundServiceCommandDecision.toAuditRecord(
    occurredAtEpochMillis: Long,
    runtimeActionResult: ForegroundProxyRuntimeActionResult,
): ForegroundServiceAuditRecord = ForegroundServiceAuditRecord(
    occurredAtEpochMillis = occurredAtEpochMillis,
    event = auditEvent,
    command = command,
    source = source,
    outcome = runtimeActionResult.toForegroundServiceAuditOutcome(),
)

private fun ForegroundProxyRuntimeActionResult.toForegroundServiceAuditOutcome(): ForegroundServiceAuditOutcome = when (this) {
    ForegroundProxyRuntimeActionResult.Started -> ForegroundServiceAuditOutcome.RuntimeStarted
    ForegroundProxyRuntimeActionResult.Stopped -> ForegroundServiceAuditOutcome.RuntimeStopped
    is ForegroundProxyRuntimeActionResult.Failed -> ForegroundServiceAuditOutcome.RuntimeFailed
    ForegroundProxyRuntimeActionResult.NotRequested -> {
        throw IllegalArgumentException("Accepted foreground service commands require a runtime audit outcome")
    }
}

private sealed interface RuntimeActionInvocationResult {
    data object Completed : RuntimeActionInvocationResult

    data class Failed(
        val exception: Exception,
    ) : RuntimeActionInvocationResult
}

private fun RuntimeActionInvocationResult.startedOrFailed(): ForegroundProxyRuntimeActionResult = when (this) {
    RuntimeActionInvocationResult.Completed -> ForegroundProxyRuntimeActionResult.Started
    is RuntimeActionInvocationResult.Failed -> ForegroundProxyRuntimeActionResult.Failed(exception)
}

private fun RuntimeActionInvocationResult.stoppedOrFailed(): ForegroundProxyRuntimeActionResult = when (this) {
    RuntimeActionInvocationResult.Completed -> ForegroundProxyRuntimeActionResult.Stopped
    is RuntimeActionInvocationResult.Failed -> ForegroundProxyRuntimeActionResult.Failed(exception)
}

object UninstalledForegroundProxyRuntimeLifecycle : ForegroundProxyRuntimeLifecycle {
    override fun startProxyRuntime(): Nothing = throw IllegalStateException("Foreground proxy runtime lifecycle is not installed")

    override fun stopProxyRuntime(): Nothing = throw IllegalStateException("Foreground proxy runtime lifecycle is not installed")
}
