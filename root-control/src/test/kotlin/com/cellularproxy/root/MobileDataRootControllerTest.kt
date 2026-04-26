package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.root.RootCommandResult
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MobileDataRootControllerTest {
    @Test
    fun `disable runs mobile data disable command and reports success`() {
        val calls = mutableListOf<MobileDataCommandCall>()
        val controller = MobileDataRootController(
            executor = mobileDataExecutor(calls) {
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "disabled",
                    stderr = "",
                )
            },
        )

        val result = controller.disable(timeoutMillis = 2_000)

        assertEquals(MobileDataRootAction.Disable, result.action)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.MobileDataDisable, result.execution?.result?.category)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
        assertEquals(
            listOf(MobileDataCommandCall(RootShellCommands.mobileDataDisable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `enable runs mobile data enable command and reports success`() {
        val calls = mutableListOf<MobileDataCommandCall>()
        val controller = MobileDataRootController(
            executor = mobileDataExecutor(calls) {
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "enabled",
                    stderr = "",
                )
            },
        )

        val result = controller.enable(timeoutMillis = 2_000)

        assertEquals(MobileDataRootAction.Enable, result.action)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.MobileDataEnable, result.execution?.result?.category)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
        assertEquals(
            listOf(MobileDataCommandCall(RootShellCommands.mobileDataEnable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `nonzero mobile data command reports command failure without throwing`() {
        val controller = MobileDataRootController(
            executor = mobileDataExecutor {
                RootCommandProcessResult.Completed(
                    exitCode = 1,
                    stdout = "",
                    stderr = "permission denied",
                )
            },
        )

        val result = controller.disable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(MobileDataRootCommandFailure.CommandFailed, result.failureReason)
        assertEquals(RootCommandOutcome.Failure, result.execution?.result?.outcome)
        assertEquals("permission denied", result.execution?.result?.stderr)
    }

    @Test
    fun `timed out mobile data command reports timeout without throwing`() {
        val controller = MobileDataRootController(
            executor = mobileDataExecutor {
                RootCommandProcessResult.TimedOut(
                    stdout = "",
                    stderr = "svc data hung",
                )
            },
        )

        val result = controller.enable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(MobileDataRootCommandFailure.CommandTimedOut, result.failureReason)
        assertEquals(RootCommandOutcome.Timeout, result.execution?.result?.outcome)
        assertEquals(null, result.execution?.result?.exitCode)
    }

    @Test
    fun `process startup exception reports execution failure and preserves terminal audit`() {
        val auditRecords = mutableListOf<com.cellularproxy.shared.root.RootCommandAuditRecord>()
        val controller = MobileDataRootController(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ ->
                    throw IllegalStateException("cannot start su")
                },
                recordAudit = auditRecords::add,
            ),
        )

        val result = controller.disable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(MobileDataRootCommandFailure.ProcessExecutionFailed, result.failureReason)
        assertNull(result.execution)
        assertEquals(listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed), auditRecords.map { it.phase })
        assertEquals(RootCommandCategory.MobileDataDisable, auditRecords.last().category)
        assertEquals(RootCommandOutcome.Failure, auditRecords.last().outcome)
    }

    @Test
    fun `configured secrets are redacted from mobile data command results`() {
        val controller = MobileDataRootController(
            executor = mobileDataExecutor {
                RootCommandProcessResult.Completed(
                    exitCode = 1,
                    stdout = "token=management-token",
                    stderr = "Cookie: session=abc",
                )
            },
        )

        val result = controller.disable(
            timeoutMillis = 1_000,
            secrets = LogRedactionSecrets(managementApiToken = "management-token"),
        )

        assertEquals("token=[REDACTED]", result.execution?.result?.stdout)
        assertEquals("Cookie: [REDACTED]", result.execution?.result?.stderr)
    }

    @Test
    fun `interrupted mobile data command restores interrupt status and propagates interruption`() {
        val interruption = InterruptedException("mobile data command interrupted")
        val controller = MobileDataRootController(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ -> throw interruption },
            ),
        )

        try {
            val thrown = assertFailsWith<InterruptedException> {
                controller.disable(timeoutMillis = 1_000)
            }

            assertSame(interruption, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelled mobile data command propagates cancellation`() {
        val cancellation = CancellationException("mobile data command cancelled")
        val controller = MobileDataRootController(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ -> throw cancellation },
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            controller.enable(timeoutMillis = 1_000)
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `invalid timeout is rejected before mobile data command execution`() {
        var processCalled = false
        val controller = MobileDataRootController(
            executor = RootCommandExecutor(
                processExecutor = RootCommandProcessExecutor { _, _ ->
                    processCalled = true
                    RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                },
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            controller.disable(timeoutMillis = 0)
        }

        assertEquals("Mobile data root command timeout must be positive", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `result invariants reject contradictory mobile data command details`() {
        val disableSuccessExecution = mobileDataExecution(
            category = RootCommandCategory.MobileDataDisable,
            exitCode = 0,
        )
        val enableSuccessExecution = mobileDataExecution(
            category = RootCommandCategory.MobileDataEnable,
            exitCode = 0,
        )
        val disableFailureExecution = mobileDataExecution(
            category = RootCommandCategory.MobileDataDisable,
            exitCode = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            MobileDataRootCommandResult(
                action = MobileDataRootAction.Disable,
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MobileDataRootCommandResult(
                action = MobileDataRootAction.Disable,
                execution = disableSuccessExecution,
                failureReason = MobileDataRootCommandFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MobileDataRootCommandResult(
                action = MobileDataRootAction.Disable,
                execution = enableSuccessExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MobileDataRootCommandResult(
                action = MobileDataRootAction.Disable,
                execution = disableFailureExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MobileDataRootCommandResult(
                action = MobileDataRootAction.Disable,
                execution = null,
                failureReason = MobileDataRootCommandFailure.CommandFailed,
            )
        }
    }

    private fun mobileDataExecutor(
        result: (MobileDataCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor =
        mobileDataExecutor(mutableListOf(), result)

    private fun mobileDataExecutor(
        calls: MutableList<MobileDataCommandCall>,
        result: (MobileDataCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor =
        RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { command, timeoutMillis ->
                assertTrue(command.category in MOBILE_DATA_CATEGORIES)
                val call = MobileDataCommandCall(command, timeoutMillis)
                calls += call
                result(call)
            },
        )

    private fun mobileDataExecution(
        category: RootCommandCategory,
        exitCode: Int,
    ): RootCommandExecution {
        val result = RootCommandResult.completed(
            category = category,
            exitCode = exitCode,
            stdout = "",
            stderr = "",
        )
        return RootCommandExecution.completed(
            result = result,
            started = com.cellularproxy.shared.root.RootCommandAuditRecord.started(category),
            completed = com.cellularproxy.shared.root.RootCommandAuditRecord.completed(result),
            rawStdout = "",
        )
    }

    private data class MobileDataCommandCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )
}

private val MOBILE_DATA_CATEGORIES = setOf(
    RootCommandCategory.MobileDataDisable,
    RootCommandCategory.MobileDataEnable,
)
