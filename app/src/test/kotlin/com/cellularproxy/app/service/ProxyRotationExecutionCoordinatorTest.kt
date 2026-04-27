package com.cellularproxy.app.service

import com.cellularproxy.root.RootAvailabilityCheckFailure
import com.cellularproxy.root.RootAvailabilityCheckResult
import com.cellularproxy.root.RootAvailabilityChecker
import com.cellularproxy.root.RootCommandExecutor
import com.cellularproxy.root.RootCommandProcessExecutor
import com.cellularproxy.root.RootCommandProcessResult
import com.cellularproxy.root.RotationRootAvailabilityProbe
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ProxyRotationExecutionCoordinatorTest {
    @Test
    fun `mobile data rotation checks cooldown then root availability and fails when root is unavailable`() {
        val rootChecks = mutableListOf<Long>()
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        RootAvailabilityCheckResult(
                            status = RootAvailabilityStatus.Unavailable,
                            failureReason = RootAvailabilityCheckFailure.ProcessExecutionFailed,
                        )
                    },
                nowElapsedMillis = { 10_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(10_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(listOf(2_000L), rootChecks)
    }

    @Test
    fun `airplane mode rotation treats unknown root availability as unavailable`() {
        val rootChecks = mutableListOf<Long>()
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        RootAvailabilityCheckResult(status = RootAvailabilityStatus.Unknown)
                    },
                nowElapsedMillis = { 11_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateAirplaneMode()

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationOperation.AirplaneMode, result.status.operation)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
    }

    @Test
    fun `available root advances accepted rotation to old public ip probe boundary`() {
        val rootChecks = mutableListOf<Long>()
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        rootAvailableCheckResult()
                    },
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.ProbingOldPublicIp, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
    }

    @Test
    fun `duplicate rotation request leaves active rotation unchanged`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )
        val first = coordinator.rotateMobileData()

        val duplicate = coordinator.rotateAirplaneMode()

        assertEquals(RotationState.ProbingOldPublicIp, first.status.state)
        assertEquals(RotationTransitionDisposition.Duplicate, duplicate.disposition)
        assertEquals(first.status, duplicate.status)
        assertEquals(RotationOperation.MobileData, controlPlane.currentStatus.operation)
    }

    @Test
    fun `cooldown rejection does not run root availability check`() {
        val rootChecks = mutableListOf<Long>()
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "198.51.100.11",
                        publicIpChanged = true,
                    ),
                initialLastTerminalElapsedMillis = 10_000,
            )
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        rootAvailableCheckResult()
                    },
                nowElapsedMillis = { 10_100 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.CooldownActive, result.status.failureReason)
        assertEquals(emptyList(), rootChecks)
    }

    @Test
    fun `invalid root availability timeout is rejected before rotation can start`() {
        val controlPlane = RotationControlPlane()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProxyRotationExecutionCoordinator(
                    controlPlane = controlPlane,
                    rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                    nowElapsedMillis = { 10_000 },
                    cooldown = 180.seconds,
                    rootAvailabilityTimeoutMillis = 0,
                )
            }

        assertEquals("Rotation root availability timeout must be positive", failure.message)
        assertEquals(RotationStatus.idle(), controlPlane.currentStatus)
    }

    @Test
    fun `secret provider failure is rejected before rotation can start`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                nowElapsedMillis = { 10_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
                secrets = { error("secrets unavailable") },
            )

        assertFailsWith<IllegalStateException> {
            coordinator.rotateMobileData()
        }
        assertEquals(RotationStatus.idle(), controlPlane.currentStatus)
    }

    @Test
    fun `root availability probe exception fails active rotation closed instead of stranding it`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe {
                        throw IllegalStateException("root check failed")
                    },
                nowElapsedMillis = { 10_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
    }

    @Test
    fun `stale root availability result returns ignored transition with actual status`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe {
                        controlPlane.applyProgress(
                            event = RotationEvent.RootUnavailable,
                            nowElapsedMillis = 10_000,
                        )
                        rootAvailableCheckResult()
                    },
                nowElapsedMillis = { 10_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Ignored, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
    }

    @Test
    fun `stale root availability exception does not fail a newer rotation`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe {
                        controlPlane.applyProgress(
                            event = RotationEvent.RootUnavailable,
                            nowElapsedMillis = 10_000,
                        )
                        controlPlane.requestStart(
                            operation = RotationOperation.AirplaneMode,
                            nowElapsedMillis = 10_001,
                            cooldown = 0.seconds,
                        )
                        throw IllegalStateException("stale root check failed")
                    },
                nowElapsedMillis = { 10_000 },
                cooldown = 0.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = coordinator.rotateMobileData()

        assertEquals(RotationTransitionDisposition.Ignored, result.disposition)
        assertEquals(RotationState.CheckingRoot, result.status.state)
        assertEquals(RotationOperation.AirplaneMode, result.status.operation)
        assertEquals(result.status, controlPlane.currentStatus)
    }
}

private fun recordingRootAvailabilityProbe(
    block: () -> RootAvailabilityCheckResult,
): RotationRootAvailabilityProbe = recordingRootAvailabilityProbe(mutableListOf(), block)

private fun recordingRootAvailabilityProbe(
    calls: MutableList<Long>,
    block: () -> RootAvailabilityCheckResult,
): RotationRootAvailabilityProbe = RotationRootAvailabilityProbe { timeoutMillis, _ ->
    calls += timeoutMillis
    block()
}

private fun rootAvailableCheckResult(): RootAvailabilityCheckResult = RootAvailabilityChecker(
    RootCommandExecutor(
        processExecutor =
            RootCommandProcessExecutor { _, _ ->
                RootCommandProcessResult.Completed(
                    exitCode = 0,
                    stdout = "0\n",
                    stderr = "",
                )
            },
    ),
).check(timeoutMillis = 1_000)
