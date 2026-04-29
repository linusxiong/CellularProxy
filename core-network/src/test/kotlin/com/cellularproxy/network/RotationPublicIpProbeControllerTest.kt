package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationControlPlaneSnapshot
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationPublicIpProbeControllerTest {
    @Test
    fun `old public IP probe success maps to old probe succeeded rotation event`() {
        val network = network("cell", NetworkCategory.Cellular)
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "203.0.113.10",
                    network = network,
                ),
            )

        val decision =
            runSuspend {
                RotationPublicIpProbeController(runner).probeOldPublicIp(
                    route = RouteTarget.Cellular,
                    endpoint = endpoint,
                )
            }

        val succeeded = assertIs<RotationPublicIpProbeDecision.OldProbeSucceeded>(decision)
        assertEquals("203.0.113.10", succeeded.publicIp)
        assertEquals(network, succeeded.network)
        assertEquals(RotationEvent.OldPublicIpProbeSucceeded("203.0.113.10"), succeeded.event)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), runner.calls)
    }

    @Test
    fun `old public IP probe failure maps to old probe failed rotation event and preserves failure metadata`() {
        val network = network("wifi", NetworkCategory.WiFi)
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(
                    reason = PublicIpProbeFailure.ResponseTimedOut,
                    network = network,
                ),
            )

        val decision =
            runSuspend {
                RotationPublicIpProbeController(runner).probeOldPublicIp(
                    route = RouteTarget.WiFi,
                    endpoint = endpoint,
                )
            }

        val failed = assertIs<RotationPublicIpProbeDecision.OldProbeFailed>(decision)
        assertEquals(PublicIpProbeFailure.ResponseTimedOut, failed.reason)
        assertEquals(network, failed.network)
        assertEquals(RotationEvent.OldPublicIpProbeFailed, failed.event)
    }

    @Test
    fun `new public IP probe success maps strict flag into new probe succeeded rotation event`() {
        val network = network("cell", NetworkCategory.Cellular)
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "203.0.113.11",
                    network = network,
                ),
            )

        val decision =
            runSuspend {
                RotationPublicIpProbeController(runner).probeNewPublicIp(
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    strictIpChangeRequired = true,
                )
            }

        val succeeded = assertIs<RotationPublicIpProbeDecision.NewProbeSucceeded>(decision)
        assertEquals("203.0.113.11", succeeded.publicIp)
        assertEquals(network, succeeded.network)
        assertEquals(
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "203.0.113.11",
                strictIpChangeRequired = true,
            ),
            succeeded.event,
        )
    }

    @Test
    fun `new public IP probe failure maps to new probe failed rotation event`() {
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp),
            )

        val decision =
            runSuspend {
                RotationPublicIpProbeController(runner).probeNewPublicIp(
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    strictIpChangeRequired = false,
                )
            }

        val failed = assertIs<RotationPublicIpProbeDecision.NewProbeFailed>(decision)
        assertEquals(PublicIpProbeFailure.InvalidPublicIp, failed.reason)
        assertEquals(null, failed.network)
        assertEquals(RotationEvent.NewPublicIpProbeFailed, failed.event)
    }

    @Test
    fun `old public IP probe coordinator applies successful old probe while phase snapshot is current`() {
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val network = network("cell", NetworkCategory.Cellular)
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network,
                ),
            )
        val controlPlane = probingOldIpControlPlane()
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = controlPlane.snapshot(),
                    route = RouteTarget.Cellular,
                    endpoint = endpoint,
                    nowElapsedMillis = 20_000,
                )
            }

        val applied = assertIs<RotationPublicIpProbeAdvanceResult.Applied>(result)
        assertIs<RotationPublicIpProbeDecision.OldProbeSucceeded>(applied.decision)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.PausingNewRequests, controlPlane.currentStatus.state)
        assertEquals("198.51.100.10", controlPlane.currentStatus.oldPublicIp)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), runner.calls)
    }

    @Test
    fun `old public IP probe coordinator applies failed old probe as terminal failure`() {
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(PublicIpProbeFailure.ResponseTimedOut),
            )
        val controlPlane = probingOldIpControlPlane()
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = controlPlane.snapshot(),
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    nowElapsedMillis = 20_000,
                )
            }

        val applied = assertIs<RotationPublicIpProbeAdvanceResult.Applied>(result)
        assertIs<RotationPublicIpProbeDecision.OldProbeFailed>(applied.decision)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.Failed, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.OldPublicIpProbeFailed, controlPlane.currentStatus.failureReason)
        assertEquals(20_000, controlPlane.lastTerminalElapsedMillis)
    }

    @Test
    fun `new public IP probe coordinator applies successful new probe and preserves strict flag`() {
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val controlPlane = probingNewIpControlPlane()
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeNewPublicIp(
                    expectedSnapshot = controlPlane.snapshot(),
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    strictIpChangeRequired = true,
                    nowElapsedMillis = 20_000,
                )
            }

        val applied = assertIs<RotationPublicIpProbeAdvanceResult.Applied>(result)
        assertEquals(
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "198.51.100.10",
                strictIpChangeRequired = true,
            ),
            applied.decision.event,
        )
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.StrictIpChangeRequired, controlPlane.currentStatus.failureReason)
        assertEquals(false, controlPlane.currentStatus.publicIpChanged)
    }

    @Test
    fun `new public IP probe coordinator applies failed new probe as resumable failure`() {
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp),
            )
        val controlPlane = probingNewIpControlPlane()
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeNewPublicIp(
                    expectedSnapshot = controlPlane.snapshot(),
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    strictIpChangeRequired = false,
                    nowElapsedMillis = 20_000,
                )
            }

        val applied = assertIs<RotationPublicIpProbeAdvanceResult.Applied>(result)
        assertIs<RotationPublicIpProbeDecision.NewProbeFailed>(applied.decision)
        assertEquals(RotationTransitionDisposition.Accepted, applied.progress.transition.disposition)
        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertEquals(RotationFailureReason.NewPublicIpProbeFailed, controlPlane.currentStatus.failureReason)
    }

    @Test
    fun `stale public IP probe snapshot is rejected before executing probe`() {
        val actualStatus = probingOldIpStatus()
        val staleSnapshot =
            RotationControlPlaneSnapshot(
                status = actualStatus,
                lastTerminalElapsedMillis = 1_000,
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus = actualStatus,
                initialLastTerminalElapsedMillis = 2_000,
            )
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = staleSnapshot,
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    nowElapsedMillis = 20_000,
                )
            }

        val stale = assertIs<RotationPublicIpProbeAdvanceResult.Stale>(result)
        assertEquals(staleSnapshot, stale.expectedSnapshot)
        assertEquals(controlPlane.snapshot(), stale.actualSnapshot)
        assertEquals(null, stale.decision)
        assertEquals(emptyList(), runner.calls)
        assertEquals(RotationState.ProbingOldPublicIp, controlPlane.currentStatus.state)
    }

    @Test
    fun `stale public IP probe result is not applied after control plane moves phases`() {
        val controlPlane = probingOldIpControlPlane()
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val runner =
            MutatingPublicIpProbeRunner(
                result =
                    PublicIpProbeResult.Success(
                        publicIp = "198.51.100.10",
                        network = network("cell", NetworkCategory.Cellular),
                    ),
            ) {
                controlPlane.applyProgress(
                    event = RotationEvent.OldPublicIpProbeFailed,
                    nowElapsedMillis = 19_000,
                )
            }
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )
        val expectedSnapshot = controlPlane.snapshot()

        val result =
            runSuspend {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = expectedSnapshot,
                    route = RouteTarget.Cellular,
                    endpoint = endpoint,
                    nowElapsedMillis = 20_000,
                )
            }

        val stale = assertIs<RotationPublicIpProbeAdvanceResult.Stale>(result)
        assertIs<RotationPublicIpProbeDecision.OldProbeSucceeded>(stale.decision)
        assertEquals(RotationState.Failed, stale.actualSnapshot.status.state)
        assertEquals(RotationFailureReason.OldPublicIpProbeFailed, stale.actualSnapshot.status.failureReason)
        assertEquals(RotationState.Failed, controlPlane.currentStatus.state)
    }

    @Test
    fun `public IP probe coordinator does not probe outside matching phase`() {
        val runner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp),
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.PausingNewRequests,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val coordinator =
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )

        val result =
            runSuspend {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = controlPlane.snapshot(),
                    route = RouteTarget.Cellular,
                    endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                    nowElapsedMillis = 20_000,
                )
            }

        assertIs<RotationPublicIpProbeAdvanceResult.NoAction>(result)
        assertEquals(emptyList(), runner.calls)
        assertEquals(RotationState.PausingNewRequests, controlPlane.currentStatus.state)
    }

    private class RecordingPublicIpProbeRunner(
        private val result: PublicIpProbeResult,
    ) : PublicIpProbeRunner {
        val calls = mutableListOf<PublicIpProbeCall>()

        override suspend fun probe(
            route: RouteTarget,
            endpoint: PublicIpProbeEndpoint,
        ): PublicIpProbeResult {
            calls += PublicIpProbeCall(route, endpoint)
            return result
        }
    }

    private class MutatingPublicIpProbeRunner(
        private val result: PublicIpProbeResult,
        private val beforeReturn: () -> Unit,
    ) : PublicIpProbeRunner {
        override suspend fun probe(
            route: RouteTarget,
            endpoint: PublicIpProbeEndpoint,
        ): PublicIpProbeResult {
            beforeReturn()
            return result
        }
    }

    private data class PublicIpProbeCall(
        val route: RouteTarget,
        val endpoint: PublicIpProbeEndpoint,
    )

    private fun network(
        id: String,
        category: NetworkCategory,
    ): NetworkDescriptor = NetworkDescriptor(
        id = id,
        category = category,
        displayName = id,
        isAvailable = true,
    )

    private fun probingOldIpControlPlane(): RotationControlPlane = RotationControlPlane(initialStatus = probingOldIpStatus())

    private fun probingOldIpStatus(): RotationStatus = RotationStatus(
        state = RotationState.ProbingOldPublicIp,
        operation = RotationOperation.MobileData,
    )

    private fun probingNewIpControlPlane(): RotationControlPlane = RotationControlPlane(
        initialStatus =
            RotationStatus(
                state = RotationState.ProbingNewPublicIp,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
            ),
    )
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { completed ->
            result = completed
        },
    )
    return result!!.getOrThrow()
}
