package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RotationNetworkReturnCoordinatorTest {
    @Test
    fun `returned selected route advances control plane to new public ip probing`() {
        val controlPlane = waitingForNetworkReturnControlPlane()
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)
        val cellular =
            network(
                id = "cell-1",
                category = NetworkCategory.Cellular,
                isAvailable = true,
            )

        val result =
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                routeTarget = RouteTarget.Cellular,
                networks = listOf(cellular),
                waitStartedElapsedMillis = 10_000,
                nowElapsedMillis = 11_000,
                networkReturnTimeout = 60.seconds,
            )

        val applied = result as RotationNetworkReturnAdvanceResult.Applied
        assertEquals(
            RotationNetworkReturnDecision.Returned(
                routeTarget = RouteTarget.Cellular,
                selectedNetwork = cellular,
            ),
            applied.decision,
        )
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ProbingNewPublicIp, controlPlane.currentStatus.state)
    }

    @Test
    fun `network return timeout advances control plane to resumable failure`() {
        val controlPlane = waitingForNetworkReturnControlPlane()
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        val result =
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = 10_000,
                nowElapsedMillis = 70_000,
                networkReturnTimeout = 60.seconds,
            )

        val applied = result as RotationNetworkReturnAdvanceResult.Applied
        assertEquals(
            RotationNetworkReturnDecision.TimedOut(routeTarget = RouteTarget.Cellular),
            applied.decision,
        )
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertEquals(
            RotationFailureReason.NetworkReturnTimedOut,
            controlPlane.currentStatus.failureReason,
        )
    }

    @Test
    fun `waiting for selected route leaves control plane waiting for network return`() {
        val controlPlane = waitingForNetworkReturnControlPlane()
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        val result =
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                routeTarget = RouteTarget.Cellular,
                networks = listOf(network(id = "wifi-1", category = NetworkCategory.WiFi, isAvailable = true)),
                waitStartedElapsedMillis = 10_000,
                nowElapsedMillis = 40_000,
                networkReturnTimeout = 60.seconds,
            )

        val waiting = result as RotationNetworkReturnAdvanceResult.Waiting
        assertEquals(
            RotationNetworkReturnDecision.Waiting(
                routeTarget = RouteTarget.Cellular,
                remainingReturnTime = 30.seconds,
            ),
            waiting.decision,
        )
        assertEquals(RotationState.WaitingForNetworkReturn, waiting.snapshot.status.state)
        assertEquals(RotationState.WaitingForNetworkReturn, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale network return observation does not advance a newer waiting phase`() {
        val currentStatus = waitingForNetworkReturnStatus()
        val staleSnapshot =
            RotationControlPlaneSnapshot(
                status = currentStatus,
                lastTerminalElapsedMillis = 1_000,
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus = currentStatus,
                initialLastTerminalElapsedMillis = 20_000,
            )
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        val result =
            coordinator.advance(
                expectedSnapshot = staleSnapshot,
                routeTarget = RouteTarget.Cellular,
                networks = listOf(network(id = "cell-1", category = NetworkCategory.Cellular, isAvailable = true)),
                waitStartedElapsedMillis = 10_000,
                nowElapsedMillis = 11_000,
                networkReturnTimeout = 60.seconds,
            )

        val stale = result as RotationNetworkReturnAdvanceResult.Stale
        assertEquals(staleSnapshot, stale.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), stale.actualSnapshot)
        assertEquals(RotationState.WaitingForNetworkReturn, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale network return observation is rejected when values match but generation differs`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.RunningEnableCommand,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        controlPlane.applyProgress(
            event =
                RotationEvent.RootCommandCompleted(
                    result =
                        com.cellularproxy.shared.root.RootCommandResult.completed(
                            category = com.cellularproxy.shared.root.RootCommandCategory.MobileDataEnable,
                            exitCode = 0,
                            stdout = "",
                            stderr = "",
                        ),
                ),
            nowElapsedMillis = 10_000,
        )
        val actualSnapshot = controlPlane.snapshot()
        val staleSnapshotWithSameValues =
            actualSnapshot.copy(
                transitionGeneration = actualSnapshot.transitionGeneration - 1,
            )
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        val result =
            coordinator.advance(
                expectedSnapshot = staleSnapshotWithSameValues,
                routeTarget = RouteTarget.Cellular,
                networks = listOf(network(id = "cell-1", category = NetworkCategory.Cellular, isAvailable = true)),
                waitStartedElapsedMillis = 10_000,
                nowElapsedMillis = 11_000,
                networkReturnTimeout = 60.seconds,
            )

        val stale = result as RotationNetworkReturnAdvanceResult.Stale
        assertEquals(staleSnapshotWithSameValues, stale.expectedSnapshot)
        assertEquals(actualSnapshot, stale.actualSnapshot)
        assertEquals(RotationState.WaitingForNetworkReturn, controlPlane.currentStatus.state)
    }

    @Test
    fun `does not evaluate network return when rotation is not waiting for network return`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.ProbingNewPublicIp,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        val result =
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = -1,
                nowElapsedMillis = -1,
                networkReturnTimeout = (-1).seconds,
            )

        assertTrue(result is RotationNetworkReturnAdvanceResult.NoAction)
        assertEquals(RotationState.ProbingNewPublicIp, controlPlane.currentStatus.state)
    }

    @Test
    fun `invalid network return timing leaves control plane waiting for network return`() {
        val controlPlane = waitingForNetworkReturnControlPlane()
        val coordinator = RotationNetworkReturnCoordinator(controlPlane = controlPlane)

        assertFailsWith<IllegalArgumentException> {
            coordinator.advance(
                expectedSnapshot = controlPlane.snapshot(),
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = -1,
                nowElapsedMillis = 11_000,
                networkReturnTimeout = 60.seconds,
            )
        }

        assertEquals(RotationState.WaitingForNetworkReturn, controlPlane.currentStatus.state)
    }

    @Test
    fun `public applied network return result rejects waiting decisions`() {
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnAdvanceResult.Applied(
                decision =
                    RotationNetworkReturnDecision.Waiting(
                        routeTarget = RouteTarget.Cellular,
                        remainingReturnTime = 1.seconds,
                    ),
                progress =
                    RotationProgressGateResult(
                        transition =
                            RotationTransitionResult(
                                disposition = RotationTransitionDisposition.Ignored,
                                status = waitingForNetworkReturnStatus(),
                            ),
                        terminalTimestampObservation =
                            TerminalRotationTimestampObservation.NotRecorded(
                                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
                            ),
                    ),
            )
        }
    }

    private fun waitingForNetworkReturnControlPlane(): RotationControlPlane = RotationControlPlane(initialStatus = waitingForNetworkReturnStatus())

    private fun waitingForNetworkReturnStatus(): RotationStatus = RotationStatus(
        state = RotationState.WaitingForNetworkReturn,
        operation = RotationOperation.MobileData,
        oldPublicIp = "198.51.100.10",
    )

    private fun network(
        id: String,
        category: NetworkCategory,
        isAvailable: Boolean,
    ): NetworkDescriptor = NetworkDescriptor(
        id = id,
        category = category,
        displayName = id,
        isAvailable = isAvailable,
    )
}
