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

class AirplaneModeRootControllerTest {
    @Test
    fun `enable runs airplane mode enable command and reports success`() {
        val calls = mutableListOf<AirplaneModeCommandCall>()
        val controller =
            AirplaneModeRootController(
                executor =
                    airplaneModeExecutor(calls) {
                        RootCommandProcessResult.Completed(
                            exitCode = 0,
                            stdout = "enabled",
                            stderr = "",
                        )
                    },
            )

        val result = controller.enable(timeoutMillis = 2_000)

        assertEquals(AirplaneModeRootAction.Enable, result.action)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.AirplaneModeEnable, result.execution?.result?.category)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
        assertEquals(
            listOf(AirplaneModeCommandCall(RootShellCommands.airplaneModeEnable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `disable runs airplane mode disable command and reports success`() {
        val calls = mutableListOf<AirplaneModeCommandCall>()
        val controller =
            AirplaneModeRootController(
                executor =
                    airplaneModeExecutor(calls) {
                        RootCommandProcessResult.Completed(
                            exitCode = 0,
                            stdout = "disabled",
                            stderr = "",
                        )
                    },
            )

        val result = controller.disable(timeoutMillis = 2_000)

        assertEquals(AirplaneModeRootAction.Disable, result.action)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.AirplaneModeDisable, result.execution?.result?.category)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
        assertEquals(
            listOf(AirplaneModeCommandCall(RootShellCommands.airplaneModeDisable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `nonzero airplane mode command reports command failure without throwing`() {
        val controller =
            AirplaneModeRootController(
                executor =
                    airplaneModeExecutor {
                        RootCommandProcessResult.Completed(
                            exitCode = 1,
                            stdout = "",
                            stderr = "permission denied",
                        )
                    },
            )

        val result = controller.enable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(AirplaneModeRootCommandFailure.CommandFailed, result.failureReason)
        assertEquals(RootCommandOutcome.Failure, result.execution?.result?.outcome)
        assertEquals("permission denied", result.execution?.result?.stderr)
    }

    @Test
    fun `timed out airplane mode command reports timeout without throwing`() {
        val controller =
            AirplaneModeRootController(
                executor =
                    airplaneModeExecutor {
                        RootCommandProcessResult.TimedOut(
                            stdout = "",
                            stderr = "airplane command hung",
                        )
                    },
            )

        val result = controller.disable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(AirplaneModeRootCommandFailure.CommandTimedOut, result.failureReason)
        assertEquals(RootCommandOutcome.Timeout, result.execution?.result?.outcome)
        assertEquals(null, result.execution?.result?.exitCode)
    }

    @Test
    fun `process startup exception reports execution failure and preserves terminal audit`() {
        val auditRecords = mutableListOf<com.cellularproxy.shared.root.RootCommandAuditRecord>()
        val controller =
            AirplaneModeRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor =
                            RootCommandProcessExecutor { _, _ ->
                                throw IllegalStateException("cannot start su")
                            },
                        recordAudit = auditRecords::add,
                    ),
            )

        val result = controller.enable(timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(AirplaneModeRootCommandFailure.ProcessExecutionFailed, result.failureReason)
        assertNull(result.execution)
        assertEquals(listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed), auditRecords.map { it.phase })
        assertEquals(RootCommandCategory.AirplaneModeEnable, auditRecords.last().category)
        assertEquals(RootCommandOutcome.Failure, auditRecords.last().outcome)
    }

    @Test
    fun `configured secrets are redacted from airplane mode command results`() {
        val controller =
            AirplaneModeRootController(
                executor =
                    airplaneModeExecutor {
                        RootCommandProcessResult.Completed(
                            exitCode = 1,
                            stdout = "token=management-token",
                            stderr = "Cookie: session=abc",
                        )
                    },
            )

        val result =
            controller.disable(
                timeoutMillis = 1_000,
                secrets = LogRedactionSecrets(managementApiToken = "management-token"),
            )

        assertEquals("token=[REDACTED]", result.execution?.result?.stdout)
        assertEquals("Cookie: [REDACTED]", result.execution?.result?.stderr)
    }

    @Test
    fun `interrupted airplane mode command restores interrupt status and propagates interruption`() {
        val interruption = InterruptedException("airplane mode command interrupted")
        val controller =
            AirplaneModeRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor = RootCommandProcessExecutor { _, _ -> throw interruption },
                    ),
            )

        try {
            val thrown =
                assertFailsWith<InterruptedException> {
                    controller.enable(timeoutMillis = 1_000)
                }

            assertSame(interruption, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelled airplane mode command propagates cancellation`() {
        val cancellation = CancellationException("airplane mode command cancelled")
        val controller =
            AirplaneModeRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor = RootCommandProcessExecutor { _, _ -> throw cancellation },
                    ),
            )

        val thrown =
            assertFailsWith<CancellationException> {
                controller.disable(timeoutMillis = 1_000)
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `invalid timeout is rejected before airplane mode command execution`() {
        var processCalled = false
        val controller =
            AirplaneModeRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor =
                            RootCommandProcessExecutor { _, _ ->
                                processCalled = true
                                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                            },
                    ),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                controller.enable(timeoutMillis = 0)
            }

        assertEquals("Airplane mode root command timeout must be positive", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `result invariants reject contradictory airplane mode command details`() {
        val enableSuccessExecution =
            airplaneModeExecution(
                category = RootCommandCategory.AirplaneModeEnable,
                exitCode = 0,
            )
        val disableSuccessExecution =
            airplaneModeExecution(
                category = RootCommandCategory.AirplaneModeDisable,
                exitCode = 0,
            )
        val enableFailureExecution =
            airplaneModeExecution(
                category = RootCommandCategory.AirplaneModeEnable,
                exitCode = 1,
            )

        assertFailsWith<IllegalArgumentException> {
            AirplaneModeRootCommandResult(
                action = AirplaneModeRootAction.Enable,
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AirplaneModeRootCommandResult(
                action = AirplaneModeRootAction.Enable,
                execution = enableSuccessExecution,
                failureReason = AirplaneModeRootCommandFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AirplaneModeRootCommandResult(
                action = AirplaneModeRootAction.Enable,
                execution = disableSuccessExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AirplaneModeRootCommandResult(
                action = AirplaneModeRootAction.Enable,
                execution = enableFailureExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AirplaneModeRootCommandResult(
                action = AirplaneModeRootAction.Enable,
                execution = null,
                failureReason = AirplaneModeRootCommandFailure.CommandFailed,
            )
        }
    }

    private fun airplaneModeExecutor(result: (AirplaneModeCommandCall) -> RootCommandProcessResult): RootCommandExecutor = airplaneModeExecutor(mutableListOf(), result)

    private fun airplaneModeExecutor(
        calls: MutableList<AirplaneModeCommandCall>,
        result: (AirplaneModeCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor = RootCommandExecutor(
        processExecutor =
            RootCommandProcessExecutor { command, timeoutMillis ->
                assertTrue(command.category in AIRPLANE_MODE_CATEGORIES)
                val call = AirplaneModeCommandCall(command, timeoutMillis)
                calls += call
                result(call)
            },
    )

    private fun airplaneModeExecution(
        category: RootCommandCategory,
        exitCode: Int,
    ): RootCommandExecution {
        val result =
            RootCommandResult.completed(
                category = category,
                exitCode = exitCode,
                stdout = "",
                stderr = "",
            )
        return RootCommandExecution.completed(
            result = result,
            started =
                com.cellularproxy.shared.root.RootCommandAuditRecord
                    .started(category),
            completed =
                com.cellularproxy.shared.root.RootCommandAuditRecord
                    .completed(result),
            rawStdout = "",
        )
    }

    private data class AirplaneModeCommandCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )
}

private val AIRPLANE_MODE_CATEGORIES =
    setOf(
        RootCommandCategory.AirplaneModeEnable,
        RootCommandCategory.AirplaneModeDisable,
    )
