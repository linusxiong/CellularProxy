package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RootAvailabilityCheckerTest {
    @Test
    fun `successful uid zero check reports root available`() {
        val calls = mutableListOf<RootAvailabilityCommandCall>()
        val checker = RootAvailabilityChecker(
            executor = rootAvailabilityExecutor(calls) {
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "0\n",
                    stderr = "",
                )
            },
        )

        val result = checker.check(timeoutMillis = 2_000)

        assertEquals(RootAvailabilityStatus.Available, result.status)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.RootAvailabilityCheck, result.execution?.result?.category)
        assertEquals(listOf(RootAvailabilityCommandCall(RootShellCommands.rootAvailabilityCheck(), 2_000)), calls)
    }

    @Test
    fun `availability is decided from raw uid output while returned command output stays redacted`() {
        val checker = RootAvailabilityChecker(
            executor = rootAvailabilityExecutor {
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "0\n",
                    stderr = "root granted",
                )
            },
        )

        val result = checker.check(
            timeoutMillis = 1_000,
            secrets = LogRedactionSecrets(managementApiToken = "0"),
        )

        assertEquals(RootAvailabilityStatus.Available, result.status)
        assertEquals("[REDACTED]\n", result.execution?.result?.stdout)
    }

    @Test
    fun `nonzero root check reports root unavailable without throwing`() {
        val checker = RootAvailabilityChecker(
            executor = rootAvailabilityExecutor {
                RootCommandProcessResult.Completed(
                    exitCode = 1,
                    stdout = "",
                    stderr = "permission denied",
                )
            },
        )

        val result = checker.check(timeoutMillis = 1_000)

        assertEquals(RootAvailabilityStatus.Unavailable, result.status)
        assertEquals(RootAvailabilityCheckFailure.CommandFailed, result.failureReason)
        assertEquals(RootCommandOutcome.Failure, result.execution?.result?.outcome)
    }

    @Test
    fun `timed out root check reports root unavailable without throwing`() {
        val checker = RootAvailabilityChecker(
            executor = rootAvailabilityExecutor {
                RootCommandProcessResult.TimedOut(
                    stdout = "",
                    stderr = "prompt timed out",
                )
            },
        )

        val result = checker.check(timeoutMillis = 1_000)

        assertEquals(RootAvailabilityStatus.Unavailable, result.status)
        assertEquals(RootAvailabilityCheckFailure.CommandTimedOut, result.failureReason)
        assertEquals(RootCommandOutcome.Timeout, result.execution?.result?.outcome)
    }

    @Test
    fun `successful command with non-root uid output reports root unavailable`() {
        val checker = RootAvailabilityChecker(
            executor = rootAvailabilityExecutor {
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "2000\n",
                    stderr = "",
                )
            },
        )

        val result = checker.check(timeoutMillis = 1_000)

        assertEquals(RootAvailabilityStatus.Unavailable, result.status)
        assertEquals(RootAvailabilityCheckFailure.InvalidUidOutput, result.failureReason)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
    }

    @Test
    fun `process startup exception reports root unavailable and preserves terminal audit`() {
        val auditRecords = mutableListOf<com.cellularproxy.shared.root.RootCommandAuditRecord>()
        val checker = RootAvailabilityChecker(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ ->
                    throw IllegalStateException("cannot start su")
                },
                recordAudit = auditRecords::add,
            ),
        )

        val result = checker.check(timeoutMillis = 1_000)

        assertEquals(RootAvailabilityStatus.Unavailable, result.status)
        assertEquals(RootAvailabilityCheckFailure.ProcessExecutionFailed, result.failureReason)
        assertNull(result.execution)
        assertEquals(listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed), auditRecords.map { it.phase })
        assertEquals(RootCommandOutcome.Failure, auditRecords.last().outcome)
    }

    @Test
    fun `interrupted root check restores interrupt status and propagates interruption`() {
        val interruption = InterruptedException("root check interrupted")
        val checker = RootAvailabilityChecker(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ -> throw interruption },
            ),
        )

        try {
            val thrown = assertFailsWith<InterruptedException> {
                checker.check(timeoutMillis = 1_000)
            }

            assertSame(interruption, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelled root check propagates cancellation`() {
        val cancellation = CancellationException("root check cancelled")
        val checker = RootAvailabilityChecker(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ -> throw cancellation },
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            checker.check(timeoutMillis = 1_000)
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `invalid timeout is rejected before root command execution`() {
        var processCalled = false
        val checker = RootAvailabilityChecker(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ ->
                    processCalled = true
                    RootCommandProcessResult.Completed(exitCode = 0, stdout = "0", stderr = "")
                },
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            checker.check(timeoutMillis = 0)
        }

        assertEquals("Root availability timeout must be positive", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `result invariants reject contradictory availability details`() {
        val availableExecution = RootCommandExecution.completed(
            result = com.cellularproxy.shared.root.RootCommandResult.completed(
                category = RootCommandCategory.RootAvailabilityCheck,
                exitCode = 0,
                stdout = "0",
                stderr = "",
            ),
            started = com.cellularproxy.shared.root.RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck),
            completed = com.cellularproxy.shared.root.RootCommandAuditRecord.completed(
                com.cellularproxy.shared.root.RootCommandResult.completed(
                    category = RootCommandCategory.RootAvailabilityCheck,
                    exitCode = 0,
                    stdout = "0",
                    stderr = "",
                ),
            ),
            rawStdout = "0",
        )

        assertFailsWith<IllegalArgumentException> {
            RootAvailabilityCheckResult(
                status = RootAvailabilityStatus.Available,
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootAvailabilityCheckResult(
                status = RootAvailabilityStatus.Available,
                execution = availableExecution,
                failureReason = RootAvailabilityCheckFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootAvailabilityCheckResult(
                status = RootAvailabilityStatus.Unavailable,
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootAvailabilityCheckResult(
                status = RootAvailabilityStatus.Unknown,
                execution = availableExecution,
                failureReason = null,
            )
        }
    }

    private fun rootAvailabilityExecutor(
        result: (RootAvailabilityCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor =
        rootAvailabilityExecutor(mutableListOf(), result)

    private fun rootAvailabilityExecutor(
        calls: MutableList<RootAvailabilityCommandCall>,
        result: (RootAvailabilityCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor =
        RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { command, timeoutMillis ->
                assertEquals(RootCommandCategory.RootAvailabilityCheck, command.category)
                assertFalse(command.argv.joinToString(" ").contains("svc data"))
                val call = RootAvailabilityCommandCall(command, timeoutMillis)
                calls += call
                result(call)
            },
        )

    private data class RootAvailabilityCommandCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )
}
