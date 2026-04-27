package com.cellularproxy.app.service

import com.cellularproxy.network.PublicIpProbeEndpoint
import com.cellularproxy.network.PublicIpProbeFailure
import com.cellularproxy.network.PublicIpProbeResult
import com.cellularproxy.network.PublicIpProbeRunner
import com.cellularproxy.proxy.server.ProxyRotationPauseActions
import com.cellularproxy.root.AirplaneModeRootController
import com.cellularproxy.root.MobileDataRootController
import com.cellularproxy.root.RootAvailabilityCheckFailure
import com.cellularproxy.root.RootAvailabilityCheckResult
import com.cellularproxy.root.RootAvailabilityChecker
import com.cellularproxy.root.RootCommandExecutor
import com.cellularproxy.root.RootCommandProcessExecutor
import com.cellularproxy.root.RootCommandProcessResult
import com.cellularproxy.root.RootShellCommand
import com.cellularproxy.root.RootShellCommands
import com.cellularproxy.root.RotationRootAvailabilityProbe
import com.cellularproxy.root.RotationRootCommandController
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyRotationExecutionCoordinatorTest {
    @Test
    fun `mobile data rotation checks cooldown then root availability and fails when root is unavailable`() {
        val rootChecks = mutableListOf<Long>()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
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
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 10_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(10_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(listOf(2_000L), rootChecks)
        assertEquals(emptyList(), publicIpRunner.calls)
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

        val result = runSuspend { coordinator.rotateAirplaneMode() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationOperation.AirplaneMode, result.status.operation)
        assertEquals(RotationFailureReason.RootUnavailable, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
    }

    @Test
    fun `available root probes old public ip and advances accepted rotation to pause boundary`() {
        val rootChecks = mutableListOf<Long>()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        rootAvailableCheckResult()
                    },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = endpoint,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.PausingNewRequests, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals("198.51.100.10", result.status.oldPublicIp)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), publicIpRunner.calls)
    }

    @Test
    fun `successful old public ip probe advances through supplied pause coordinator`() {
        val rootChecks = mutableListOf<Long>()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        rootAvailableCheckResult()
                    },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = endpoint,
                pauseActions = pauseActions,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.DrainingConnections, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals("198.51.100.10", result.status.oldPublicIp)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), publicIpRunner.calls)
        assertTrue(pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(0, pauseActions.resumeCalls)
    }

    @Test
    fun `successful pause advances through supplied connection drain when no proxy exchanges are active`() {
        val rootChecks = mutableListOf<Long>()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe(rootChecks) {
                        rootAvailableCheckResult()
                    },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = endpoint,
                pauseActions = pauseActions,
                activeProxyExchanges = { 0 },
                maxConnectionDrainTime = 30.seconds,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.RunningDisableCommand, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals("198.51.100.10", result.status.oldPublicIp)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(2_000L), rootChecks)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), publicIpRunner.calls)
        assertTrue(pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(0, pauseActions.resumeCalls)
    }

    @Test
    fun `connection drain can continue after active proxy exchanges finish`() {
        var now = 12_000L
        var activeProxyExchanges = 1L
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { activeProxyExchanges },
                maxConnectionDrainTime = 30.seconds,
                nowElapsedMillis = { now },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val waiting = runSuspend { coordinator.rotateMobileData() }
        activeProxyExchanges = 0
        now = 13_000
        val drained = coordinator.advanceConnectionDrain()

        assertEquals(RotationTransitionDisposition.Accepted, waiting.disposition)
        assertEquals(RotationState.DrainingConnections, waiting.status.state)
        assertEquals(RotationTransitionDisposition.Accepted, drained.disposition)
        assertEquals(RotationState.RunningDisableCommand, drained.status.state)
        assertEquals("198.51.100.10", drained.status.oldPublicIp)
        assertEquals(drained.status, controlPlane.currentStatus)
        assertTrue(pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(0, pauseActions.resumeCalls)
    }

    @Test
    fun `connection drain can continue after maximum drain time elapses`() {
        var now = 12_000L
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner =
                    RecordingPublicIpProbeRunner(
                        PublicIpProbeResult.Success(
                            publicIp = "198.51.100.10",
                            network = network("cell", NetworkCategory.Cellular),
                        ),
                    ),
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { 1 },
                maxConnectionDrainTime = 30.seconds,
                nowElapsedMillis = { now },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val waiting = runSuspend { coordinator.rotateMobileData() }
        now = 42_000
        val drained = coordinator.advanceConnectionDrain()

        assertEquals(RotationState.DrainingConnections, waiting.status.state)
        assertEquals(RotationTransitionDisposition.Accepted, drained.disposition)
        assertEquals(RotationState.RunningDisableCommand, drained.status.state)
        assertEquals(drained.status, controlPlane.currentStatus)
        assertTrue(pauseActions.proxyRequestsPaused)
    }

    @Test
    fun `connection drain timeout starts after pause rather than rotation start`() {
        var now = 12_000L
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner =
                    MutatingPublicIpProbeRunner(
                        result =
                            PublicIpProbeResult.Success(
                                publicIp = "198.51.100.10",
                                network = network("cell", NetworkCategory.Cellular),
                            ),
                    ) {
                        now = 42_000
                    },
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { 1 },
                maxConnectionDrainTime = 30.seconds,
                nowElapsedMillis = { now },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val waiting = runSuspend { coordinator.rotateMobileData() }
        now = 42_001
        val stillDraining = coordinator.advanceConnectionDrain()

        assertEquals(RotationState.DrainingConnections, waiting.status.state)
        assertEquals(RotationTransitionDisposition.Ignored, stillDraining.disposition)
        assertEquals(RotationState.DrainingConnections, stillDraining.status.state)
        assertEquals(stillDraining.status, controlPlane.currentStatus)
        assertTrue(pauseActions.proxyRequestsPaused)
    }

    @Test
    fun `drained rotation can run configured disable root command and wait for toggle delay`() {
        val rootCommandCalls = mutableListOf<RotationRootCommandCall>()
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner =
                    RecordingPublicIpProbeRunner(
                        PublicIpProbeResult.Success(
                            publicIp = "198.51.100.10",
                            network = network("cell", NetworkCategory.Cellular),
                        ),
                    ),
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { 0 },
                maxConnectionDrainTime = 30.seconds,
                rootCommandController =
                    rotationRootCommandController(rootCommandCalls) {
                        RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                    },
                rootCommandTimeoutMillis = 4_000,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.WaitingForToggleDelay, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals("198.51.100.10", result.status.oldPublicIp)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(RotationRootCommandCall(RootShellCommands.mobileDataDisable(), 4_000)), rootCommandCalls)
        assertTrue(pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(0, pauseActions.resumeCalls)
    }

    @Test
    fun `failed disable root command resumes proxy requests and fails the rotation`() {
        val rootCommandCalls = mutableListOf<RotationRootCommandCall>()
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner =
                    RecordingPublicIpProbeRunner(
                        PublicIpProbeResult.Success(
                            publicIp = "198.51.100.10",
                            network = network("cell", NetworkCategory.Cellular),
                        ),
                    ),
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { 0 },
                maxConnectionDrainTime = 30.seconds,
                rootCommandController =
                    rotationRootCommandController(rootCommandCalls) {
                        RootCommandProcessResult.Completed(exitCode = 1, stdout = "", stderr = "failed")
                    },
                rootCommandTimeoutMillis = 4_000,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.RootCommandFailed, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(12_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(listOf(RotationRootCommandCall(RootShellCommands.mobileDataDisable(), 4_000)), rootCommandCalls)
        assertTrue(!pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(1, pauseActions.resumeCalls)
    }

    @Test
    fun `stale disable root command failure still resumes proxy requests`() {
        val rootCommandCalls = mutableListOf<RotationRootCommandCall>()
        val controlPlane = RotationControlPlane()
        val pauseActions = RecordingPauseActions()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner =
                    RecordingPublicIpProbeRunner(
                        PublicIpProbeResult.Success(
                            publicIp = "198.51.100.10",
                            network = network("cell", NetworkCategory.Cellular),
                        ),
                    ),
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                pauseActions = pauseActions,
                activeProxyExchanges = { 0 },
                maxConnectionDrainTime = 30.seconds,
                rootCommandController =
                    rotationRootCommandController(rootCommandCalls) {
                        controlPlane.applyProgress(
                            event = RotationEvent.RootCommandFailedToStart(RootShellCommands.mobileDataDisable().category),
                            nowElapsedMillis = 11_999,
                        )
                        RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                    },
                rootCommandTimeoutMillis = 4_000,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.RootCommandFailed, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(12_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(listOf(RotationRootCommandCall(RootShellCommands.mobileDataDisable(), 4_000)), rootCommandCalls)
        assertTrue(!pauseActions.proxyRequestsPaused)
        assertEquals(1, pauseActions.pauseCalls)
        assertEquals(1, pauseActions.resumeCalls)
    }

    @Test
    fun `old public ip probe failure fails active rotation closed`() {
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Failed(
                    reason = PublicIpProbeFailure.ResponseTimedOut,
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationOperation.MobileData, result.status.operation)
        assertEquals(RotationFailureReason.OldPublicIpProbeFailed, result.status.failureReason)
        assertEquals(12_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(result.status, controlPlane.currentStatus)
    }

    @Test
    fun `old public ip probe exception fails active rotation closed instead of stranding it`() {
        val controlPlane = RotationControlPlane()
        val publicIpRunner =
            ThrowingPublicIpProbeRunner(
                failure = IllegalStateException("probe failed"),
            )
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.OldPublicIpProbeFailed, result.status.failureReason)
        assertEquals(12_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(
            listOf(PublicIpProbeCall(RouteTarget.Cellular, PublicIpProbeEndpoint(host = "ip.example"))),
            publicIpRunner.calls,
        )
    }

    @Test
    fun `old public ip probe cancellation is propagated`() {
        val controlPlane = RotationControlPlane()
        val publicIpRunner =
            ThrowingPublicIpProbeRunner(
                failure = CancellationException("probe cancelled"),
            )
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        assertFailsWith<CancellationException> {
            runSuspend { coordinator.rotateMobileData() }
        }
    }

    @Test
    fun `root availability cancellation is propagated`() {
        val controlPlane = RotationControlPlane()
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe =
                    recordingRootAvailabilityProbe {
                        throw CancellationException("root check cancelled")
                    },
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        assertFailsWith<CancellationException> {
            runSuspend { coordinator.rotateMobileData() }
        }
    }

    @Test
    fun `stale old public ip probe result returns ignored transition with actual status`() {
        val controlPlane = RotationControlPlane()
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val publicIpRunner =
            MutatingPublicIpProbeRunner(
                result =
                    PublicIpProbeResult.Success(
                        publicIp = "198.51.100.10",
                        network = network("cell", NetworkCategory.Cellular),
                    ),
            ) {
                controlPlane.applyProgress(
                    event = RotationEvent.OldPublicIpProbeFailed,
                    nowElapsedMillis = 12_000,
                )
            }
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = endpoint,
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Ignored, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.OldPublicIpProbeFailed, result.status.failureReason)
        assertEquals(result.status, controlPlane.currentStatus)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), publicIpRunner.calls)
    }

    @Test
    fun `duplicate rotation request leaves active rotation unchanged`() {
        val controlPlane = RotationControlPlane()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
        val coordinator =
            ProxyRotationExecutionCoordinator(
                controlPlane = controlPlane,
                rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 12_000 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )
        val first = runSuspend { coordinator.rotateMobileData() }

        val duplicate = runSuspend { coordinator.rotateAirplaneMode() }

        assertEquals(RotationState.PausingNewRequests, first.status.state)
        assertEquals(RotationTransitionDisposition.Duplicate, duplicate.disposition)
        assertEquals(first.status, duplicate.status)
        assertEquals(RotationOperation.MobileData, controlPlane.currentStatus.operation)
    }

    @Test
    fun `cooldown rejection does not run root availability check`() {
        val rootChecks = mutableListOf<Long>()
        val publicIpRunner =
            RecordingPublicIpProbeRunner(
                PublicIpProbeResult.Success(
                    publicIp = "198.51.100.10",
                    network = network("cell", NetworkCategory.Cellular),
                ),
            )
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
                publicIpProbeRunner = publicIpRunner,
                route = RouteTarget.Cellular,
                publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                nowElapsedMillis = { 10_100 },
                cooldown = 180.seconds,
                rootAvailabilityTimeoutMillis = 2_000,
            )

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Accepted, result.disposition)
        assertEquals(RotationState.Failed, result.status.state)
        assertEquals(RotationFailureReason.CooldownActive, result.status.failureReason)
        assertEquals(emptyList(), rootChecks)
        assertEquals(emptyList(), publicIpRunner.calls)
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
    fun `connection drain configuration requires pause actions`() {
        val controlPlane = RotationControlPlane()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProxyRotationExecutionCoordinator(
                    controlPlane = controlPlane,
                    rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                    activeProxyExchanges = { 0 },
                    maxConnectionDrainTime = 30.seconds,
                    nowElapsedMillis = { 10_000 },
                    cooldown = 180.seconds,
                    rootAvailabilityTimeoutMillis = 2_000,
                )
            }

        assertEquals("Rotation connection drain requires pause actions", failure.message)
        assertEquals(RotationStatus.idle(), controlPlane.currentStatus)
    }

    @Test
    fun `root command configuration requires connection drain configuration`() {
        val controlPlane = RotationControlPlane()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProxyRotationExecutionCoordinator(
                    controlPlane = controlPlane,
                    rootAvailabilityProbe = recordingRootAvailabilityProbe { rootAvailableCheckResult() },
                    pauseActions = RecordingPauseActions(),
                    rootCommandController =
                        rotationRootCommandController(mutableListOf()) {
                            RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                        },
                    rootCommandTimeoutMillis = 4_000,
                    nowElapsedMillis = { 10_000 },
                    cooldown = 180.seconds,
                    rootAvailabilityTimeoutMillis = 2_000,
                )
            }

        assertEquals("Rotation root command execution requires connection drain configuration", failure.message)
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
            runSuspend { coordinator.rotateMobileData() }
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

        val result = runSuspend { coordinator.rotateMobileData() }

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

        val result = runSuspend { coordinator.rotateMobileData() }

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

        val result = runSuspend { coordinator.rotateMobileData() }

        assertEquals(RotationTransitionDisposition.Ignored, result.disposition)
        assertEquals(RotationState.CheckingRoot, result.status.state)
        assertEquals(RotationOperation.AirplaneMode, result.status.operation)
        assertEquals(result.status, controlPlane.currentStatus)
    }
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

private class ThrowingPublicIpProbeRunner(
    private val failure: Exception,
) : PublicIpProbeRunner {
    val calls = mutableListOf<PublicIpProbeCall>()

    override suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult {
        calls += PublicIpProbeCall(route, endpoint)
        throw failure
    }
}

private class MutatingPublicIpProbeRunner(
    private val result: PublicIpProbeResult,
    private val beforeReturn: () -> Unit,
) : PublicIpProbeRunner {
    val calls = mutableListOf<PublicIpProbeCall>()

    override suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult {
        calls += PublicIpProbeCall(route, endpoint)
        beforeReturn()
        return result
    }
}

private data class PublicIpProbeCall(
    val route: RouteTarget,
    val endpoint: PublicIpProbeEndpoint,
)

private data class RotationRootCommandCall(
    val command: RootShellCommand,
    val timeoutMillis: Long,
)

private class RecordingPauseActions : ProxyRotationPauseActions {
    var proxyRequestsPaused = false
        private set
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set

    override fun pauseProxyRequests(): RotationEvent.NewRequestsPaused {
        pauseCalls += 1
        proxyRequestsPaused = true
        return RotationEvent.NewRequestsPaused
    }

    override fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed {
        resumeCalls += 1
        proxyRequestsPaused = false
        return RotationEvent.ProxyRequestsResumed
    }
}

private fun network(
    id: String,
    category: NetworkCategory,
): NetworkDescriptor = NetworkDescriptor(
    id = id,
    category = category,
    displayName = id,
    isAvailable = true,
)

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

private fun rotationRootCommandController(
    calls: MutableList<RotationRootCommandCall>,
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

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { completed ->
            result = completed
        },
    )
    return result!!.getOrThrow()
}
