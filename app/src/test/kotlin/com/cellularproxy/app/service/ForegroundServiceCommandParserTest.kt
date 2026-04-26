package com.cellularproxy.app.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundServiceCommandParserTest {
    @Test
    fun `start action maps to app-sourced start command with audit event`() {
        val result = ForegroundServiceCommandParser.parse(ForegroundServiceActions.START_PROXY)

        assertEquals(
            ForegroundServiceCommandResult.Accepted(
                ForegroundServiceCommandDecision(
                    command = ForegroundServiceCommand.Start,
                    source = ForegroundServiceCommandSource.App,
                    auditEvent = ForegroundServiceAuditEvent.StartRequested,
                ),
            ),
            result,
        )
    }

    @Test
    fun `stop action maps to app-sourced stop command with audit event`() {
        val result = ForegroundServiceCommandParser.parse(ForegroundServiceActions.STOP_PROXY)

        assertEquals(
            ForegroundServiceCommandResult.Accepted(
                ForegroundServiceCommandDecision(
                    command = ForegroundServiceCommand.Stop,
                    source = ForegroundServiceCommandSource.App,
                    auditEvent = ForegroundServiceAuditEvent.StopRequested,
                ),
            ),
            result,
        )
    }

    @Test
    fun `notification stop action maps to notification-sourced stop command with distinct audit event`() {
        val result = ForegroundServiceCommandParser.parse(ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION)

        assertEquals(
            ForegroundServiceCommandResult.Accepted(
                ForegroundServiceCommandDecision(
                    command = ForegroundServiceCommand.Stop,
                    source = ForegroundServiceCommandSource.Notification,
                    auditEvent = ForegroundServiceAuditEvent.NotificationStopRequested,
                ),
            ),
            result,
        )
    }

    @Test
    fun `missing blank and unknown actions are ignored`() {
        assertEquals(ForegroundServiceCommandResult.Ignored, ForegroundServiceCommandParser.parse(null))
        assertEquals(ForegroundServiceCommandResult.Ignored, ForegroundServiceCommandParser.parse(""))
        assertEquals(ForegroundServiceCommandResult.Ignored, ForegroundServiceCommandParser.parse(" "))
        assertEquals(
            ForegroundServiceCommandResult.Ignored,
            ForegroundServiceCommandParser.parse("com.cellularproxy.action.UNKNOWN"),
        )
    }
}
