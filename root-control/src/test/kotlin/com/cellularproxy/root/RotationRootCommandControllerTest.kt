package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationOperation
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RotationRootCommandControllerTest {
    @Test
    fun `mobile data disable command runs svc data disable and exposes rotation event`() {
        val calls = mutableListOf<RotationRootCommandCall>()
        val controller =
            rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "disabled", stderr = "")
            }

        val result =
            controller.runDisableCommand(
                operation = RotationOperation.MobileData,
                timeoutMillis = 2_000,
            )

        assertEquals(RotationOperation.MobileData, result.operation)
        assertEquals(RotationRootCommandPhase.DisableCommand, result.phase)
        assertTrue(result.succeeded)
        assertNull(result.failureReason)
        assertEquals(RootCommandCategory.MobileDataDisable, result.execution?.result?.category)
        assertEquals(
            RotationEvent.RootCommandCompleted(requireNotNull(result.execution).result),
            result.toRotationEvent(),
        )
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.mobileDataDisable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `mobile data enable command runs svc data enable`() {
        val calls = mutableListOf<RotationRootCommandCall>()
        val controller =
            rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "enabled", stderr = "")
            }

        val result =
            controller.runEnableCommand(
                operation = RotationOperation.MobileData,
                timeoutMillis = 2_000,
            )

        assertTrue(result.succeeded)
        assertEquals(RootCommandCategory.MobileDataEnable, result.execution?.result?.category)
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.mobileDataEnable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `airplane mode disable command enables airplane mode for the first rotation command`() {
        val calls = mutableListOf<RotationRootCommandCall>()
        val controller =
            rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "airplane on", stderr = "")
            }

        val result =
            controller.runDisableCommand(
                operation = RotationOperation.AirplaneMode,
                timeoutMillis = 3_000,
            )

        assertTrue(result.succeeded)
        assertEquals(RootCommandCategory.AirplaneModeEnable, result.execution?.result?.category)
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.airplaneModeEnable(), 3_000)),
            calls,
        )
    }

    @Test
    fun `airplane mode enable command disables airplane mode for the second rotation command`() {
        val calls = mutableListOf<RotationRootCommandCall>()
        val controller =
            rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "airplane off", stderr = "")
            }

        val result =
            controller.runEnableCommand(
                operation = RotationOperation.AirplaneMode,
                timeoutMillis = 3_000,
            )

        assertTrue(result.succeeded)
        assertEquals(RootCommandCategory.AirplaneModeDisable, result.execution?.result?.category)
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.airplaneModeDisable(), 3_000)),
            calls,
        )
    }

    @Test
    fun `command failures and timeouts map to rotation root command failures`() {
        val failedController =
            rotationRootCommandController {
                RootCommandProcessResult.Completed(exitCode = 1, stdout = "", stderr = "denied")
            }
        val timedOutController =
            rotationRootCommandController {
                RootCommandProcessResult.TimedOut(stdout = "", stderr = "hung")
            }

        val failed =
            failedController.runDisableCommand(
                operation = RotationOperation.MobileData,
                timeoutMillis = 1_000,
            )
        val timedOut =
            timedOutController.runEnableCommand(
                operation = RotationOperation.AirplaneMode,
                timeoutMillis = 1_000,
            )

        assertFalse(failed.succeeded)
        assertEquals(RotationRootCommandFailure.CommandFailed, failed.failureReason)
        assertEquals(RootCommandOutcome.Failure, failed.execution?.result?.outcome)
        assertEquals(RotationEvent.RootCommandCompleted(requireNotNull(failed.execution).result), failed.toRotationEvent())
        assertFalse(timedOut.succeeded)
        assertEquals(RotationRootCommandFailure.CommandTimedOut, timedOut.failureReason)
        assertEquals(RootCommandOutcome.Timeout, timedOut.execution?.result?.outcome)
        assertEquals(RotationEvent.RootCommandCompleted(requireNotNull(timedOut.execution).result), timedOut.toRotationEvent())
    }

    @Test
    fun `process execution failure exposes failed to start rotation event`() {
        val controller =
            RotationRootCommandController(
                mobileDataController =
                    MobileDataRootController(
                        RootCommandExecutor(
                            processExecutor =
                                RootCommandProcessExecutor { _, _ ->
                                    throw IllegalStateException("cannot start")
                                },
                        ),
                    ),
                airplaneModeController =
                    AirplaneModeRootController(
                        RootCommandExecutor(
                            processExecutor =
                                RootCommandProcessExecutor { _, _ ->
                                    error("should not run")
                                },
                        ),
                    ),
            )

        val result =
            controller.runDisableCommand(
                operation = RotationOperation.MobileData,
                timeoutMillis = 1_000,
            )

        assertFalse(result.succeeded)
        assertEquals(RotationRootCommandFailure.ProcessExecutionFailed, result.failureReason)
        assertNull(result.execution)
        assertEquals(
            RotationEvent.RootCommandFailedToStart(RootCommandCategory.MobileDataDisable),
            result.toRotationEvent(),
        )
    }

    @Test
    fun `configured secrets are redacted through delegated root controllers`() {
        val controller =
            rotationRootCommandController {
                RootCommandProcessResult.Completed(
                    exitCode = 1,
                    stdout = "token=management-token",
                    stderr = "Cookie: session=abc",
                )
            }

        val result =
            controller.runEnableCommand(
                operation = RotationOperation.MobileData,
                timeoutMillis = 1_000,
                secrets = LogRedactionSecrets(managementApiToken = "management-token"),
            )

        assertEquals("token=[REDACTED]", result.execution?.result?.stdout)
        assertEquals("Cookie: [REDACTED]", result.execution?.result?.stderr)
    }

    @Test
    fun `interruption and cancellation propagate from delegated root controllers`() {
        val interruption = InterruptedException("rotation command interrupted")
        val interrupted =
            rotationRootCommandController {
                throw interruption
            }
        try {
            val thrown =
                assertFailsWith<InterruptedException> {
                    interrupted.runDisableCommand(
                        operation = RotationOperation.MobileData,
                        timeoutMillis = 1_000,
                    )
                }

            assertSame(interruption, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        val cancellation = CancellationException("rotation command cancelled")
        val cancelled =
            rotationRootCommandController {
                throw cancellation
            }

        val thrown =
            assertFailsWith<CancellationException> {
                cancelled.runEnableCommand(
                    operation = RotationOperation.AirplaneMode,
                    timeoutMillis = 1_000,
                )
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `invalid timeout is rejected before running any rotation root command`() {
        var processCalled = false
        val controller =
            rotationRootCommandController {
                processCalled = true
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
            }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                controller.runEnableCommand(
                    operation = RotationOperation.MobileData,
                    timeoutMillis = 0,
                )
            }

        assertEquals("Rotation root command timeout must be positive", failure.message)
        assertFalse(processCalled)
    }

    @Test
    fun `result invariants reject contradictory rotation command details`() {
        val disableSuccess = rotationRootExecution(RootCommandCategory.MobileDataDisable, exitCode = 0)
        val enableSuccess = rotationRootExecution(RootCommandCategory.MobileDataEnable, exitCode = 0)
        val disableFailure = rotationRootExecution(RootCommandCategory.MobileDataDisable, exitCode = 1)
        val disableTimeout = rotationRootTimedOutExecution(RootCommandCategory.MobileDataDisable)

        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandResult(
                operation = RotationOperation.MobileData,
                phase = RotationRootCommandPhase.DisableCommand,
                execution = null,
                failureReason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandResult(
                operation = RotationOperation.MobileData,
                phase = RotationRootCommandPhase.DisableCommand,
                execution = enableSuccess,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandResult(
                operation = RotationOperation.MobileData,
                phase = RotationRootCommandPhase.DisableCommand,
                execution = disableSuccess,
                failureReason = RotationRootCommandFailure.CommandFailed,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandResult(
                operation = RotationOperation.MobileData,
                phase = RotationRootCommandPhase.DisableCommand,
                execution = disableFailure,
                failureReason = RotationRootCommandFailure.CommandTimedOut,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandResult(
                operation = RotationOperation.MobileData,
                phase = RotationRootCommandPhase.DisableCommand,
                execution = disableTimeout,
                failureReason = RotationRootCommandFailure.ProcessExecutionFailed,
            )
        }
    }
}

private data class RotationRootCommandCall(
    val command: RootShellCommand,
    val timeoutMillis: Long,
)

private fun rotationRootCommandController(
    calls: MutableList<RotationRootCommandCall> = mutableListOf(),
    processResult: (RootShellCommand) -> RootCommandProcessResult,
): RotationRootCommandController {
    val executor =
        RootCommandExecutor(
            processExecutor =
                RootCommandProcessExecutor { command, timeoutMillis ->
                    calls += RotationRootCommandCall(command, timeoutMillis)
                    processResult(command)
                },
        )
    return RotationRootCommandController(
        mobileDataController = MobileDataRootController(executor),
        airplaneModeController = AirplaneModeRootController(executor),
    )
}

private fun rotationRootExecution(
    category: RootCommandCategory,
    exitCode: Int,
): RootCommandExecution {
    val command = RootShellCommand.trusted(category, listOf("su", "-c", "test"))
    return RootCommandExecutor(
        processExecutor =
            RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.Completed(exitCode = exitCode, stdout = "", stderr = "")
            },
    ).execute(command, timeoutMillis = 1_000)
}

private fun rotationRootTimedOutExecution(category: RootCommandCategory): RootCommandExecution {
    val command = RootShellCommand.trusted(category, listOf("su", "-c", "test"))
    return RootCommandExecutor(
        processExecutor =
            RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.TimedOut(stdout = "", stderr = "")
            },
    ).execute(command, timeoutMillis = 1_000)
}
