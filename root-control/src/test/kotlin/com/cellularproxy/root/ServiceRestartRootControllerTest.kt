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

class ServiceRestartRootControllerTest {
    @Test
    fun `restart runs service restart command and reports success`() {
        val calls = mutableListOf<ServiceRestartCommandCall>()
        val controller =
            ServiceRestartRootController(
                executor =
                    serviceRestartExecutor(calls) {
                        RootCommandProcessResult.Completed(
                            exitCode = 0,
                            stdout = "Events injected: 1",
                            stderr = "",
                        )
                    },
            )

        val result =
            controller.restart(
                packageName = "com.example.app",
                timeoutMillis = 2_000,
            )

        assertEquals("com.example.app", result.packageName)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.ServiceRestart, result.execution?.result?.category)
        assertEquals(RootCommandOutcome.Success, result.execution?.result?.outcome)
        assertEquals(
            listOf(ServiceRestartCommandCall(RootShellCommands.serviceRestart("com.example.app"), 2_000)),
            calls,
        )
    }

    @Test
    fun `invalid package name is rejected before service restart command execution`() {
        var processCalled = false
        val controller =
            ServiceRestartRootController(
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
                controller.restart(packageName = "com.example; reboot", timeoutMillis = 1_000)
            }

        assertEquals("Package name contains unsafe characters", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `nonzero service restart command reports command failure without throwing`() {
        val controller =
            ServiceRestartRootController(
                executor =
                    serviceRestartExecutor {
                        RootCommandProcessResult.Completed(
                            exitCode = 1,
                            stdout = "",
                            stderr = "package not found",
                        )
                    },
            )

        val result = controller.restart(packageName = "com.example.app", timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(ServiceRestartRootCommandFailure.CommandFailed, result.failureReason)
        assertEquals(RootCommandOutcome.Failure, result.execution?.result?.outcome)
        assertEquals("package not found", result.execution?.result?.stderr)
    }

    @Test
    fun `timed out service restart command reports timeout without throwing`() {
        val controller =
            ServiceRestartRootController(
                executor =
                    serviceRestartExecutor {
                        RootCommandProcessResult.TimedOut(
                            stdout = "",
                            stderr = "monkey hung",
                        )
                    },
            )

        val result = controller.restart(packageName = "com.example.app", timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(ServiceRestartRootCommandFailure.CommandTimedOut, result.failureReason)
        assertEquals(RootCommandOutcome.Timeout, result.execution?.result?.outcome)
        assertEquals(null, result.execution?.result?.exitCode)
    }

    @Test
    fun `process startup exception reports execution failure and preserves terminal audit`() {
        val auditRecords = mutableListOf<com.cellularproxy.shared.root.RootCommandAuditRecord>()
        val controller =
            ServiceRestartRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor =
                            RootCommandProcessExecutor { _, _ ->
                                throw IllegalStateException("cannot start su")
                            },
                        recordAudit = auditRecords::add,
                    ),
            )

        val result = controller.restart(packageName = "com.example.app", timeoutMillis = 1_000)

        assertFalse(result.succeeded)
        assertEquals(ServiceRestartRootCommandFailure.ProcessExecutionFailed, result.failureReason)
        assertNull(result.execution)
        assertEquals(listOf(RootCommandAuditPhase.Started, RootCommandAuditPhase.Completed), auditRecords.map { it.phase })
        assertEquals(RootCommandCategory.ServiceRestart, auditRecords.last().category)
        assertEquals(RootCommandOutcome.Failure, auditRecords.last().outcome)
    }

    @Test
    fun `configured secrets are redacted from service restart command results`() {
        val controller =
            ServiceRestartRootController(
                executor =
                    serviceRestartExecutor {
                        RootCommandProcessResult.Completed(
                            exitCode = 1,
                            stdout = "token=management-token",
                            stderr = "Cookie: session=abc",
                        )
                    },
            )

        val result =
            controller.restart(
                packageName = "com.example.app",
                timeoutMillis = 1_000,
                secrets = LogRedactionSecrets(managementApiToken = "management-token"),
            )

        assertEquals("token=[REDACTED]", result.execution?.result?.stdout)
        assertEquals("Cookie: [REDACTED]", result.execution?.result?.stderr)
    }

    @Test
    fun `interrupted service restart command restores interrupt status and propagates interruption`() {
        val interruption = InterruptedException("service restart command interrupted")
        val controller =
            ServiceRestartRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor = RootCommandProcessExecutor { _, _ -> throw interruption },
                    ),
            )

        try {
            val thrown =
                assertFailsWith<InterruptedException> {
                    controller.restart(packageName = "com.example.app", timeoutMillis = 1_000)
                }

            assertSame(interruption, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelled service restart command propagates cancellation`() {
        val cancellation = CancellationException("service restart command cancelled")
        val controller =
            ServiceRestartRootController(
                executor =
                    RootCommandExecutor(
                        processExecutor = RootCommandProcessExecutor { _, _ -> throw cancellation },
                    ),
            )

        val thrown =
            assertFailsWith<CancellationException> {
                controller.restart(packageName = "com.example.app", timeoutMillis = 1_000)
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `invalid timeout is rejected before service restart command execution`() {
        var processCalled = false
        val controller =
            ServiceRestartRootController(
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
                controller.restart(packageName = "com.example.app", timeoutMillis = 0)
            }

        assertEquals("Service restart root command timeout must be positive", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `result invariants reject contradictory service restart command details`() {
        val successExecution = serviceRestartExecution(exitCode = 0)
        val failureExecution = serviceRestartExecution(exitCode = 1)
        val mobileDataSuccessExecution =
            serviceRestartExecution(
                category = RootCommandCategory.MobileDataEnable,
                exitCode = 0,
            )
        val timeoutExecution = serviceRestartTimeoutExecution()

        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = successExecution,
                failureReason = ServiceRestartRootCommandFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = mobileDataSuccessExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = failureExecution,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = null,
                failureReason = ServiceRestartRootCommandFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = successExecution,
                failureReason = ServiceRestartRootCommandFailure.CommandTimedOut,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = successExecution,
                failureReason = ServiceRestartRootCommandFailure.ProcessExecutionFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceRestartRootCommandResult(
                packageName = "com.example.app",
                execution = timeoutExecution,
                failureReason = null,
            )
        }
    }

    private fun serviceRestartExecutor(result: (ServiceRestartCommandCall) -> RootCommandProcessResult): RootCommandExecutor = serviceRestartExecutor(mutableListOf(), result)

    private fun serviceRestartExecutor(
        calls: MutableList<ServiceRestartCommandCall>,
        result: (ServiceRestartCommandCall) -> RootCommandProcessResult,
    ): RootCommandExecutor = RootCommandExecutor(
        processExecutor =
            RootCommandProcessExecutor { command, timeoutMillis ->
                assertEquals(RootCommandCategory.ServiceRestart, command.category)
                val call = ServiceRestartCommandCall(command, timeoutMillis)
                calls += call
                result(call)
            },
    )

    private fun serviceRestartExecution(
        category: RootCommandCategory = RootCommandCategory.ServiceRestart,
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

    private fun serviceRestartTimeoutExecution(): RootCommandExecution {
        val result =
            RootCommandResult.timedOut(
                category = RootCommandCategory.ServiceRestart,
                stdout = "",
                stderr = "",
            )
        return RootCommandExecution.completed(
            result = result,
            started =
                com.cellularproxy.shared.root.RootCommandAuditRecord
                    .started(RootCommandCategory.ServiceRestart),
            completed =
                com.cellularproxy.shared.root.RootCommandAuditRecord
                    .completed(result),
            rawStdout = "",
        )
    }

    private data class ServiceRestartCommandCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )
}
