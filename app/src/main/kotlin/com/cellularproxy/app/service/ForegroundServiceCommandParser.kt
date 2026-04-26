package com.cellularproxy.app.service

object ForegroundServiceActions {
    const val START_PROXY: String = "com.cellularproxy.action.START_PROXY"
    const val STOP_PROXY: String = "com.cellularproxy.action.STOP_PROXY"
    const val STOP_PROXY_FROM_NOTIFICATION: String = "com.cellularproxy.action.STOP_PROXY_FROM_NOTIFICATION"
}

object ForegroundServiceCommandParser {
    fun parse(action: String?): ForegroundServiceCommandResult = when (action) {
        ForegroundServiceActions.START_PROXY -> ForegroundServiceCommandResult.Accepted(
            ForegroundServiceCommandDecision(
                command = ForegroundServiceCommand.Start,
                source = ForegroundServiceCommandSource.App,
                auditEvent = ForegroundServiceAuditEvent.StartRequested,
            ),
        )

        ForegroundServiceActions.STOP_PROXY -> ForegroundServiceCommandResult.Accepted(
            ForegroundServiceCommandDecision(
                command = ForegroundServiceCommand.Stop,
                source = ForegroundServiceCommandSource.App,
                auditEvent = ForegroundServiceAuditEvent.StopRequested,
            ),
        )

        ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION -> ForegroundServiceCommandResult.Accepted(
            ForegroundServiceCommandDecision(
                command = ForegroundServiceCommand.Stop,
                source = ForegroundServiceCommandSource.Notification,
                auditEvent = ForegroundServiceAuditEvent.NotificationStopRequested,
            ),
        )

        else -> ForegroundServiceCommandResult.Ignored
    }
}

sealed interface ForegroundServiceCommandResult {
    data object Ignored : ForegroundServiceCommandResult

    data class Accepted(
        val decision: ForegroundServiceCommandDecision,
    ) : ForegroundServiceCommandResult
}

data class ForegroundServiceCommandDecision(
    val command: ForegroundServiceCommand,
    val source: ForegroundServiceCommandSource,
    val auditEvent: ForegroundServiceAuditEvent,
)

enum class ForegroundServiceCommand {
    Start,
    Stop,
}

enum class ForegroundServiceCommandSource {
    App,
    Notification,
}

enum class ForegroundServiceAuditEvent {
    StartRequested,
    StopRequested,
    NotificationStopRequested,
}
