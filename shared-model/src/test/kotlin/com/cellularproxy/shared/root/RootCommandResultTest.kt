package com.cellularproxy.shared.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootCommandResultTest {
    @Test
    fun `completed root command with zero exit code is successful`() {
        val result =
            RootCommandResult.completed(
                category = RootCommandCategory.RootAvailabilityCheck,
                exitCode = 0,
                stdout = "uid=0(root)",
                stderr = "",
            )

        assertEquals(RootCommandOutcome.Success, result.outcome)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("uid=0(root)", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `completed root command with nonzero exit code is failure`() {
        val result =
            RootCommandResult.completed(
                category = RootCommandCategory.MobileDataDisable,
                exitCode = 1,
                stdout = "",
                stderr = "svc failed",
            )

        assertEquals(RootCommandOutcome.Failure, result.outcome)
        assertEquals(1, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("svc failed", result.stderr)
    }

    @Test
    fun `timed out root command has timeout outcome and no exit code`() {
        val result =
            RootCommandResult.timedOut(
                category = RootCommandCategory.AirplaneModeEnable,
                stdout = "started",
                stderr = "Authorization: bearer-token",
            )

        assertEquals(RootCommandOutcome.Timeout, result.outcome)
        assertNull(result.exitCode)
        assertTrue(result.timedOut)
        assertEquals("Authorization: [REDACTED]", result.stderr)
    }

    @Test
    fun `root command output is redacted with configured secrets before storage`() {
        val secrets =
            LogRedactionSecrets(
                managementApiToken = "management-token",
                proxyCredential = "proxy-user:proxy-password",
                cloudflareTunnelToken = "cloudflare-token",
            )

        val result =
            RootCommandResult.completed(
                category = RootCommandCategory.ServiceRestart,
                exitCode = 0,
                stdout = "restart management-token proxy-user:proxy-password",
                stderr = "tunnel=cloudflare-token path=/api/status?token=abc",
                secrets = secrets,
            )

        assertEquals("restart [REDACTED] [REDACTED]", result.stdout)
        assertEquals("tunnel=[REDACTED] path=/api/status?[REDACTED]", result.stderr)
    }

    @Test
    fun `audit records represent start and terminal root command events`() {
        val started = RootCommandAuditRecord.started(RootCommandCategory.MobileDataEnable)
        val completed =
            RootCommandAuditRecord.completed(
                RootCommandResult.completed(
                    category = RootCommandCategory.MobileDataEnable,
                    exitCode = 0,
                    stdout = "enabled",
                    stderr = "",
                ),
            )

        assertEquals(RootCommandAuditPhase.Started, started.phase)
        assertEquals(RootCommandCategory.MobileDataEnable, started.category)
        assertNull(started.outcome)
        assertNull(started.exitCode)
        assertNull(started.stdout)
        assertNull(started.stderr)

        assertEquals(RootCommandAuditPhase.Completed, completed.phase)
        assertEquals(RootCommandCategory.MobileDataEnable, completed.category)
        assertEquals(RootCommandOutcome.Success, completed.outcome)
        assertEquals(0, completed.exitCode)
        assertEquals("enabled", completed.stdout)
        assertEquals("", completed.stderr)
    }

    @Test
    fun `audit records represent root process start failures with redaction`() {
        val failed =
            RootCommandAuditRecord.failedToStart(
                category = RootCommandCategory.RootAvailabilityCheck,
                reason = "process failed for management-token",
                secrets = LogRedactionSecrets(managementApiToken = "management-token"),
            )

        assertEquals(RootCommandAuditPhase.Completed, failed.phase)
        assertEquals(RootCommandCategory.RootAvailabilityCheck, failed.category)
        assertEquals(RootCommandOutcome.Failure, failed.outcome)
        assertNull(failed.exitCode)
        assertEquals("", failed.stdout)
        assertEquals("process failed for [REDACTED]", failed.stderr)
    }
}
