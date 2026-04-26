package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationProgressGateResult
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.TerminalRotationTimestampNotRecordedReason
import com.cellularproxy.shared.rotation.TerminalRotationTimestampObservation
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RotationRootCommandCoordinatorTest {
    @Test
    fun `running disable command executes operation disable command and advances to toggle delay`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val calls = mutableListOf<RotationRootCommandCall>()
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "disabled", stderr = "")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
        )

        val applied = result as RotationRootCommandAdvanceResult.Applied
        assertEquals(RotationRootCommandPhase.DisableCommand, applied.commandResult.phase)
        assertEquals(RootCommandCategory.MobileDataDisable, applied.commandResult.execution?.result?.category)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.mobileDataDisable(), 2_000)),
            calls,
        )
    }

    @Test
    fun `running enable command executes operation enable command and advances to network return`() {
        val controlPlane = runningCommandControlPlane(
            state = RotationState.RunningEnableCommand,
            operation = RotationOperation.AirplaneMode,
        )
        val calls = mutableListOf<RotationRootCommandCall>()
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController(calls) {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "airplane off", stderr = "")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 3_000,
            nowElapsedMillis = 20_000,
        )

        val applied = result as RotationRootCommandAdvanceResult.Applied
        assertEquals(RotationRootCommandPhase.EnableCommand, applied.commandResult.phase)
        assertEquals(RootCommandCategory.AirplaneModeDisable, applied.commandResult.execution?.result?.category)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.WaitingForNetworkReturn, controlPlane.currentStatus.state)
        assertEquals(
            listOf(RotationRootCommandCall(RootShellCommands.airplaneModeDisable(), 3_000)),
            calls,
        )
    }

    @Test
    fun `root command failure advances to resumable rotation failure`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                RootCommandProcessResult.Completed(exitCode = 1, stdout = "", stderr = "denied")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
        )

        val applied = result as RotationRootCommandAdvanceResult.Applied
        assertFalse(applied.commandResult.succeeded)
        assertEquals(RotationRootCommandFailure.CommandFailed, applied.commandResult.failureReason)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.RootCommandFailed, controlPlane.currentStatus.failureReason)
    }

    @Test
    fun `outside root command phase does not execute command`() {
        val controlPlane = runningCommandControlPlane(RotationState.WaitingForToggleDelay)
        var commandExecuted = false
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                commandExecuted = true
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
        )

        assertTrue(result is RotationRootCommandAdvanceResult.NoAction)
        assertFalse(commandExecuted)
        assertEquals(RotationState.WaitingForToggleDelay, controlPlane.currentStatus.state)
    }

    @Test
    fun `process execution failure advances to resumable root command failure`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                throw IllegalStateException("cannot start")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
        )

        val applied = result as RotationRootCommandAdvanceResult.Applied
        assertEquals(RotationRootCommandFailure.ProcessExecutionFailed, applied.commandResult.failureReason)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.RootCommandFailed, controlPlane.currentStatus.failureReason)
    }

    @Test
    fun `stale command result is not applied after control plane leaves command phase`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                controlPlane.applyProgress(
                    event = RotationEvent.RootCommandFailedToStart(RootCommandCategory.MobileDataDisable),
                    nowElapsedMillis = 19_000,
                )
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "disabled", stderr = "")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
        )

        val stale = result as RotationRootCommandAdvanceResult.Stale
        assertEquals(RotationRootCommandPhase.DisableCommand, stale.commandResult.phase)
        assertEquals(RotationState.ResumingProxyRequests, stale.snapshot.status.state)
        assertEquals(RotationFailureReason.RootCommandFailed, stale.snapshot.status.failureReason)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale command result is not applied to a newer matching command phase`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                controlPlane.applyProgress(
                    event = RotationEvent.RootCommandFailedToStart(RootCommandCategory.MobileDataDisable),
                    nowElapsedMillis = 19_000,
                )
                controlPlane.applyProgress(
                    event = RotationEvent.ProxyRequestsResumed,
                    nowElapsedMillis = 19_001,
                )
                controlPlane.requestStart(
                    operation = RotationOperation.MobileData,
                    nowElapsedMillis = 20_000,
                    cooldown = 0.seconds,
                )
                controlPlane.applyProgress(
                    event = RotationEvent.RootAvailable,
                    nowElapsedMillis = 20_001,
                )
                controlPlane.applyProgress(
                    event = RotationEvent.OldPublicIpProbeSucceeded("203.0.113.25"),
                    nowElapsedMillis = 20_002,
                )
                controlPlane.applyProgress(
                    event = RotationEvent.NewRequestsPaused,
                    nowElapsedMillis = 20_003,
                )
                controlPlane.applyProgress(
                    event = RotationEvent.ConnectionsDrained,
                    nowElapsedMillis = 20_004,
                )
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "disabled", stderr = "")
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 21_000,
        )

        val stale = result as RotationRootCommandAdvanceResult.Stale
        assertEquals(RotationRootCommandPhase.DisableCommand, stale.commandResult.phase)
        assertEquals(RotationState.RunningDisableCommand, stale.snapshot.status.state)
        assertEquals(RotationOperation.MobileData, stale.snapshot.status.operation)
        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `invalid timing or timeout leaves state unchanged before command event is applied`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
            },
            controlPlane = controlPlane,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.runCurrentCommand(
                timeoutMillis = 0,
                nowElapsedMillis = 20_000,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.runCurrentCommand(
                timeoutMillis = 2_000,
                nowElapsedMillis = -1,
            )
        }

        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
    }

    @Test
    fun `applied result invariants reject ignored root command transitions`() {
        val commandResult = RotationRootCommandResult(
            operation = RotationOperation.MobileData,
            phase = RotationRootCommandPhase.DisableCommand,
            execution = rootCommandExecution(RootCommandCategory.MobileDataDisable),
        )
        val ignoredProgress = RotationProgressGateResult(
            transition = com.cellularproxy.shared.rotation.RotationTransitionResult(
                disposition = RotationTransitionDisposition.Ignored,
                status = RotationStatus(
                    state = RotationState.RunningDisableCommand,
                    operation = RotationOperation.MobileData,
                    oldPublicIp = "198.51.100.10",
                ),
            ),
            terminalTimestampObservation = TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            RotationRootCommandAdvanceResult.Applied(
                commandResult = commandResult,
                progress = ignoredProgress,
            )
        }
    }

    @Test
    fun `configured secrets are passed to root command controller`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val secrets = LogRedactionSecrets(managementApiToken = "management-token")
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                RootCommandProcessResult.Completed(
                    exitCode = 1,
                    stdout = "",
                    stderr = "token=management-token",
                )
            },
            controlPlane = controlPlane,
        )

        val result = coordinator.runCurrentCommand(
            timeoutMillis = 2_000,
            nowElapsedMillis = 20_000,
            secrets = secrets,
        )

        val applied = result as RotationRootCommandAdvanceResult.Applied
        assertEquals("token=[REDACTED]", applied.commandResult.execution?.result?.stderr)
    }

    @Test
    fun `serious errors from root command execution are propagated`() {
        val controlPlane = runningCommandControlPlane(RotationState.RunningDisableCommand)
        val serious = OutOfMemoryError("fatal")
        val coordinator = RotationRootCommandCoordinator(
            commandController = rotationRootCommandController {
                throw serious
            },
            controlPlane = controlPlane,
        )

        val thrown = assertFailsWith<OutOfMemoryError> {
            coordinator.runCurrentCommand(
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )
        }

        assertSame(serious, thrown)
        assertEquals(RotationState.RunningDisableCommand, controlPlane.currentStatus.state)
    }

    private data class RotationRootCommandCall(
        val command: RootShellCommand,
        val timeoutMillis: Long,
    )

    private fun rotationRootCommandController(
        calls: MutableList<RotationRootCommandCall> = mutableListOf(),
        processResult: (RootShellCommand) -> RootCommandProcessResult,
    ): RotationRootCommandController {
        val executor = RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { command, timeoutMillis ->
                calls += RotationRootCommandCall(command, timeoutMillis)
                processResult(command)
            },
        )
        return RotationRootCommandController(
            mobileDataController = MobileDataRootController(executor),
            airplaneModeController = AirplaneModeRootController(executor),
        )
    }

    private fun runningCommandControlPlane(
        state: RotationState,
        operation: RotationOperation = RotationOperation.MobileData,
    ): RotationControlPlane =
        RotationControlPlane(
            initialStatus = RotationStatus(
                state = state,
                operation = operation,
                oldPublicIp = "198.51.100.10",
            ),
        )

    private fun rootCommandExecution(category: RootCommandCategory): RootCommandExecution =
        RootCommandExecutor(
            processExecutor = RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
            },
        ).execute(rootShellCommand(category), timeoutMillis = 1_000)

    private fun rootShellCommand(category: RootCommandCategory): RootShellCommand =
        when (category) {
            RootCommandCategory.MobileDataDisable -> RootShellCommands.mobileDataDisable()
            RootCommandCategory.MobileDataEnable -> RootShellCommands.mobileDataEnable()
            RootCommandCategory.AirplaneModeEnable -> RootShellCommands.airplaneModeEnable()
            RootCommandCategory.AirplaneModeDisable -> RootShellCommands.airplaneModeDisable()
            RootCommandCategory.RootAvailabilityCheck,
            RootCommandCategory.ServiceRestart,
            -> error("Unexpected rotation root command category: $category")
        }
}
