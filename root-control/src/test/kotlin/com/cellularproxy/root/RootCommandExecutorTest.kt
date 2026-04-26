package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RootCommandExecutorTest {
    @Test
    fun `completed command emits started and completed audit records`() {
        val calls = mutableListOf<RootCommandProcessCall>()
        val auditRecords = mutableListOf<RootCommandAuditRecord>()
        val executor = RootCommandExecutor(
            processExecutor = recordingProcessExecutor(calls) {
                assertEquals(RootCommandAuditPhase.Started, auditRecords.single().phase)
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "uid=0(root)",
                    stderr = "",
                )
            },
            recordAudit = auditRecords::add,
        )
        val command = RootShellCommand.trusted(
            category = RootCommandCategory.RootAvailabilityCheck,
            argv = listOf("su", "-c", "id -u"),
        )

        val execution = executor.execute(command, timeoutMillis = 5_000)

        assertEquals(listOf(RootCommandProcessCall(command, 5_000)), calls)
        assertEquals(RootCommandAuditPhase.Started, execution.auditRecords[0].phase)
        assertEquals(RootCommandCategory.RootAvailabilityCheck, execution.auditRecords[0].category)
        assertEquals(RootCommandAuditPhase.Completed, execution.auditRecords[1].phase)
        assertEquals(execution.auditRecords, auditRecords)
        assertEquals(RootCommandOutcome.Success, execution.result.outcome)
        assertEquals(0, execution.result.exitCode)
        assertEquals("uid=0(root)", execution.result.stdout)
        assertFalse(execution.result.timedOut)
    }

    @Test
    fun `process executor exception still emits started audit record before propagating`() {
        val failure = IllegalStateException("process startup failed")
        val auditRecords = mutableListOf<RootCommandAuditRecord>()
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ -> throw failure },
            recordAudit = auditRecords::add,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            executor.execute(RootShellCommands.mobileDataEnable(), timeoutMillis = 1_000)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed), auditRecords.map { it.phase })
        assertEquals(RootCommandCategory.MobileDataEnable, auditRecords.last().category)
        assertEquals(RootCommandOutcome.Failure, auditRecords.last().outcome)
        assertEquals(null, auditRecords.last().exitCode)
        assertEquals("Root command process execution failed: IllegalStateException", auditRecords.last().stderr)
    }

    @Test
    fun `nonzero exit code is reported as a failed root command`() {
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.Completed(
                    exitCode = 12,
                    stdout = "",
                    stderr = "permission denied",
                )
            },
        )

        val execution = executor.execute(RootShellCommands.mobileDataDisable(), timeoutMillis = 1_000)

        assertEquals(RootCommandCategory.MobileDataDisable, execution.result.category)
        assertEquals(RootCommandOutcome.Failure, execution.result.outcome)
        assertEquals(12, execution.result.exitCode)
        assertEquals("permission denied", execution.auditRecords.last().stderr)
    }

    @Test
    fun `completed command output and audit record are redacted`() {
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "management-token",
                    stderr = "Cookie: session=abc",
                )
            },
        )

        val execution = executor.execute(
            command = RootShellCommands.serviceRestart(packageName = "com.example.app"),
            timeoutMillis = 1_000,
            secrets = LogRedactionSecrets(managementApiToken = "management-token"),
        )

        assertEquals("[REDACTED]", execution.result.stdout)
        assertEquals("Cookie: [REDACTED]", execution.result.stderr)
        assertEquals("[REDACTED]", execution.auditRecords.last().stdout)
        assertEquals("Cookie: [REDACTED]", execution.auditRecords.last().stderr)
    }

    @Test
    fun `timeout outcome has no exit code and preserves redacted partial output`() {
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.TimedOut(
                    stdout = "token=management-token",
                    stderr = "Proxy-Authorization: Basic abc",
                )
            },
        )

        val execution = executor.execute(
            command = RootShellCommands.airplaneModeEnable(),
            timeoutMillis = 1_000,
            secrets = LogRedactionSecrets(managementApiToken = "management-token"),
        )

        assertEquals(RootCommandOutcome.Timeout, execution.result.outcome)
        assertEquals(null, execution.result.exitCode)
        assertEquals("token=[REDACTED]", execution.result.stdout)
        assertEquals("Proxy-Authorization: [REDACTED]", execution.auditRecords.last().stderr)
    }

    @Test
    fun `invalid command arguments and timeout are rejected before process execution`() {
        var processCalled = false
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ ->
                processCalled = true
                RootCommandProcessResult.Completed(0, "", "")
            },
        )

        assertFailsWithMessage("Root command argv must not be empty") {
            RootShellCommand.trusted(RootCommandCategory.ServiceRestart, emptyList())
        }
        assertFailsWithMessage("Root command argv entries must not be blank") {
            RootShellCommand.trusted(RootCommandCategory.ServiceRestart, listOf("su", " "))
        }
        assertFailsWithMessage("Root command timeout must be positive") {
            executor.execute(RootShellCommands.serviceRestart(packageName = "com.example.app"), timeoutMillis = 0)
        }
        assertFalse(processCalled)
    }

    @Test
    fun `root command execution factory rejects mismatched audit phases`() {
        val result = com.cellularproxy.shared.root.RootCommandResult.completed(
            category = RootCommandCategory.RootAvailabilityCheck,
            exitCode = 0,
            stdout = "",
            stderr = "",
        )
        val completed = RootCommandAuditRecord.completed(result)

        assertFailsWithMessage("Started audit record must have started phase") {
            RootCommandExecution.completed(
                result = result,
                started = completed,
                completed = completed,
            )
        }
    }

    @Test
    fun `root shell command factories render spec backed Android commands`() {
        assertEquals(
            RootShellCommand.trusted(RootCommandCategory.RootAvailabilityCheck, listOf("su", "-c", "id -u")),
            RootShellCommands.rootAvailabilityCheck(),
        )
        assertEquals(
            RootShellCommand.trusted(RootCommandCategory.MobileDataDisable, listOf("su", "-c", "svc data disable")),
            RootShellCommands.mobileDataDisable(),
        )
        assertEquals(
            RootShellCommand.trusted(RootCommandCategory.MobileDataEnable, listOf("su", "-c", "svc data enable")),
            RootShellCommands.mobileDataEnable(),
        )
        assertEquals(
            RootShellCommand.trusted(
                RootCommandCategory.AirplaneModeEnable,
                listOf("su", "-c", "cmd connectivity airplane-mode enable"),
            ),
            RootShellCommands.airplaneModeEnable(),
        )
        assertEquals(
            RootShellCommand.trusted(
                RootCommandCategory.AirplaneModeDisable,
                listOf("su", "-c", "cmd connectivity airplane-mode disable"),
            ),
            RootShellCommands.airplaneModeDisable(),
        )
        assertEquals(
            RootShellCommand.trusted(
                RootCommandCategory.ServiceRestart,
                listOf(
                    "su",
                    "-c",
                    "am force-stop com.cellularproxy && monkey -p com.cellularproxy 1",
                ),
            ),
            RootShellCommands.serviceRestart(packageName = "com.cellularproxy"),
        )
    }

    @Test
    fun `service restart package names are restricted to Android package syntax`() {
        assertFailsWithMessage("Package name must not be blank") {
            RootShellCommands.serviceRestart(packageName = " ")
        }
        assertFailsWithMessage("Package name contains unsafe characters") {
            RootShellCommands.serviceRestart(packageName = "com.example; reboot")
        }
    }

    private fun recordingProcessExecutor(
        calls: MutableList<RootCommandProcessCall>,
        result: (RootCommandProcessCall) -> RootCommandProcessResult,
    ): RootCommandProcessExecutor =
        RootCommandProcessExecutor { command, timeoutMillis ->
            val call = RootCommandProcessCall(command, timeoutMillis)
            calls += call
            result(call)
        }

    private fun assertFailsWithMessage(
        message: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertEquals(message, failure.message)
    }

    private data class RootCommandProcessCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )
}
