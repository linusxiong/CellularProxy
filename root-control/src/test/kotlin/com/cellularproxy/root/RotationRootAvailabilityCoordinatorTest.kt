package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class RotationRootAvailabilityCoordinatorTest {
    @Test
    fun `available root check advances checking-root rotation to old public-ip probing`() {
        val controlPlane = checkingRootControlPlane()
        val calls = mutableListOf<RotationRootAvailabilityCall>()
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController(
                        calls = calls,
                        result = rootAvailableCheckResult(rawStdout = "0\n"),
                    ),
                controlPlane = controlPlane,
            )
        val secrets = LogRedactionSecrets(managementApiToken = "management-token")

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = controlPlane.snapshot(),
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
                secrets = secrets,
            )

        val applied = assertIs<RotationRootAvailabilityAdvanceResult.Applied>(result)
        assertIs<RotationRootAvailabilityDecision.Available>(applied.decision)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ProbingOldPublicIp, controlPlane.currentStatus.state)
        assertEquals(
            listOf(RotationRootAvailabilityCall(2_000, secrets)),
            calls,
        )
    }

    @Test
    fun `unavailable root check advances checking-root rotation to terminal failure and records timestamp`() {
        val controlPlane = checkingRootControlPlane()
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController(
                        result = rootUnavailableCheckResult(),
                    ),
                controlPlane = controlPlane,
            )

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = controlPlane.snapshot(),
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )

        val applied = assertIs<RotationRootAvailabilityAdvanceResult.Applied>(result)
        val unavailable = assertIs<RotationRootAvailabilityDecision.Unavailable>(applied.decision)
        assertEquals(RootAvailabilityCheckFailure.CommandFailed, unavailable.failureReason)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.Failed, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.RootUnavailable, controlPlane.currentStatus.failureReason)
        assertEquals(20_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `stale snapshot is rejected before executing root check`() {
        val staleSnapshot =
            RotationControlPlaneSnapshot(
                status =
                    RotationStatus(
                        state = RotationState.CheckingRoot,
                        operation = RotationOperation.MobileData,
                    ),
                lastTerminalElapsedMillis = null,
                transitionGeneration = 0,
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.CheckingRoot,
                        operation = RotationOperation.MobileData,
                    ),
            )
        controlPlane.applyProgress(
            event = RotationEvent.RootAvailable,
            nowElapsedMillis = 10_000,
        )
        var rootCheckExecuted = false
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController {
                        rootCheckExecuted = true
                        rootAvailableCheckResult(rawStdout = "0")
                    },
                controlPlane = controlPlane,
            )

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = staleSnapshot,
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )

        val stale = assertIs<RotationRootAvailabilityAdvanceResult.Stale>(result)
        assertEquals(staleSnapshot, stale.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), stale.actualSnapshot)
        assertEquals(null, stale.decision)
        assertEquals(false, rootCheckExecuted)
    }

    @Test
    fun `stale snapshot is rejected when values match but generation differs`() {
        val controlPlane = RotationControlPlane()
        controlPlane.requestStart(
            operation = RotationOperation.MobileData,
            nowElapsedMillis = 10_000,
            cooldown = 180.seconds,
        )
        val actualSnapshot = controlPlane.snapshot()
        val staleSnapshotWithSameValues =
            actualSnapshot.copy(
                transitionGeneration = actualSnapshot.transitionGeneration - 1,
            )
        var rootCheckExecuted = false
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController {
                        rootCheckExecuted = true
                        rootAvailableCheckResult(rawStdout = "0")
                    },
                controlPlane = controlPlane,
            )

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = staleSnapshotWithSameValues,
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )

        val stale = assertIs<RotationRootAvailabilityAdvanceResult.Stale>(result)
        assertEquals(staleSnapshotWithSameValues, stale.expectedSnapshot)
        assertEquals(actualSnapshot, stale.actualSnapshot)
        assertEquals(null, stale.decision)
        assertEquals(false, rootCheckExecuted)
        assertEquals(RotationState.CheckingRoot, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale root check result is not applied after control plane leaves checking-root phase`() {
        val controlPlane = checkingRootControlPlane()
        val expectedSnapshot = controlPlane.snapshot()
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController {
                        controlPlane.applyProgress(
                            event = RotationEvent.RootUnavailable,
                            nowElapsedMillis = 19_000,
                        )
                        rootAvailableCheckResult(rawStdout = "0")
                    },
                controlPlane = controlPlane,
            )

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = expectedSnapshot,
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )

        val stale = assertIs<RotationRootAvailabilityAdvanceResult.Stale>(result)
        assertEquals(expectedSnapshot, stale.expectedSnapshot)
        assertEquals(RotationState.Failed, stale.actualSnapshot.status.state)
        assertEquals(RotationFailureReason.RootUnavailable, stale.actualSnapshot.status.failureReason)
        assertIs<RotationRootAvailabilityDecision.Available>(stale.decision)
        assertEquals(RotationState.Failed, controlPlane.currentStatus.state)
        assertEquals(19_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `outside checking-root phase does not execute root check`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.ProbingOldPublicIp,
                        operation = RotationOperation.MobileData,
                    ),
            )
        var rootCheckExecuted = false
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController {
                        rootCheckExecuted = true
                        rootAvailableCheckResult(rawStdout = "0")
                    },
                controlPlane = controlPlane,
            )

        val result =
            coordinator.checkRootAvailability(
                expectedSnapshot = controlPlane.snapshot(),
                timeoutMillis = 2_000,
                nowElapsedMillis = 20_000,
            )

        val noAction = assertIs<RotationRootAvailabilityAdvanceResult.NoAction>(result)
        assertEquals(controlPlane.snapshot(), noAction.snapshot)
        assertEquals(false, rootCheckExecuted)
        assertEquals(RotationState.ProbingOldPublicIp, controlPlane.currentStatus.state)
    }

    @Test
    fun `invalid timing leaves checking-root rotation unchanged before root check`() {
        val controlPlane = checkingRootControlPlane()
        var rootCheckExecuted = false
        val coordinator =
            RotationRootAvailabilityCoordinator(
                availabilityController =
                    rotationRootAvailabilityController {
                        rootCheckExecuted = true
                        rootAvailableCheckResult(rawStdout = "0")
                    },
                controlPlane = controlPlane,
            )

        assertFailsWith<IllegalArgumentException> {
            coordinator.checkRootAvailability(
                expectedSnapshot = controlPlane.snapshot(),
                timeoutMillis = 0,
                nowElapsedMillis = 20_000,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.checkRootAvailability(
                expectedSnapshot = controlPlane.snapshot(),
                timeoutMillis = 2_000,
                nowElapsedMillis = -1,
            )
        }

        assertEquals(false, rootCheckExecuted)
        assertEquals(RotationState.CheckingRoot, controlPlane.currentStatus.state)
    }

    @Test
    fun `applied result invariant rejects ignored rotation progress`() {
        val decision = RotationRootAvailabilityDecision.Available(rootAvailableCheckResult(rawStdout = "0"))
        val ignoredProgress =
            RotationControlPlane().applyProgress(
                event = RotationEvent.RootAvailable,
                nowElapsedMillis = 20_000,
            )

        assertFailsWith<IllegalArgumentException> {
            RotationRootAvailabilityAdvanceResult.Applied(
                decision = decision,
                progress = ignoredProgress,
            )
        }
    }

    private data class RotationRootAvailabilityCall(
        val timeoutMillis: Long,
        val secrets: LogRedactionSecrets,
    )

    private fun rotationRootAvailabilityController(
        calls: MutableList<RotationRootAvailabilityCall> = mutableListOf(),
        result: () -> RootAvailabilityCheckResult,
    ): RotationRootAvailabilityController = RotationRootAvailabilityController(
        probe =
            RotationRootAvailabilityProbe { timeoutMillis, secrets ->
                calls += RotationRootAvailabilityCall(timeoutMillis, secrets)
                result()
            },
    )

    private fun rotationRootAvailabilityController(
        calls: MutableList<RotationRootAvailabilityCall> = mutableListOf(),
        result: RootAvailabilityCheckResult,
    ): RotationRootAvailabilityController = rotationRootAvailabilityController(calls = calls) { result }

    private fun checkingRootControlPlane(): RotationControlPlane = RotationControlPlane(
        initialStatus =
            RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.MobileData,
            ),
    )

    private fun rootAvailableCheckResult(rawStdout: String): RootAvailabilityCheckResult = RootAvailabilityCheckResult(
        status = RootAvailabilityStatus.Available,
        execution =
            rootAvailabilityExecution(
                result =
                    RootCommandResult.completed(
                        category = RootCommandCategory.RootAvailabilityCheck,
                        exitCode = 0,
                        stdout = rawStdout,
                        stderr = "",
                    ),
                rawStdout = rawStdout,
            ),
    )

    private fun rootUnavailableCheckResult(): RootAvailabilityCheckResult = RootAvailabilityCheckResult(
        status = RootAvailabilityStatus.Unavailable,
        execution =
            rootAvailabilityExecution(
                result =
                    RootCommandResult.completed(
                        category = RootCommandCategory.RootAvailabilityCheck,
                        exitCode = 1,
                        stdout = "",
                        stderr = "permission denied",
                    ),
                rawStdout = "",
            ),
        failureReason = RootAvailabilityCheckFailure.CommandFailed,
    )

    private fun rootAvailabilityExecution(
        result: RootCommandResult,
        rawStdout: String,
    ): RootCommandExecution = RootCommandExecution.completed(
        result = result,
        started = RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck),
        completed = RootCommandAuditRecord.completed(result),
        rawStdout = rawStdout,
    )
}
