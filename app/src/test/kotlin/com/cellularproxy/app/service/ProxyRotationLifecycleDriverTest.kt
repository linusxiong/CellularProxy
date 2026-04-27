package com.cellularproxy.app.service

import com.cellularproxy.network.PublicIpProbeEndpoint
import com.cellularproxy.network.PublicIpProbeResult
import com.cellularproxy.network.PublicIpProbeRunner
import com.cellularproxy.proxy.server.ProxyRotationPauseActions
import com.cellularproxy.root.AirplaneModeRootController
import com.cellularproxy.root.MobileDataRootController
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
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyRotationLifecycleDriverTest {
    @Test
    fun `mobile data request schedules continuation and completes configured active rotation`() {
        val scheduledExecutor = ScheduledThreadPoolExecutor(1)
        var now = 12_000L
        val rootCommandCalls = mutableListOf<DriverRootCommandCall>()
        val pauseActions = DriverRecordingPauseActions()
        val controlPlane = RotationControlPlane()
        val driver =
            ProxyRotationLifecycleDriver(
                coordinator =
                    ProxyRotationExecutionCoordinator(
                        controlPlane = controlPlane,
                        rootAvailabilityProbe = availableRootProbe(),
                        publicIpProbeRunner =
                            DriverSequencedPublicIpProbeRunner(
                                PublicIpProbeResult.Success(
                                    publicIp = "198.51.100.10",
                                    network = driverNetwork("cell"),
                                ),
                                PublicIpProbeResult.Success(
                                    publicIp = "198.51.100.11",
                                    network = driverNetwork("cell"),
                                ),
                            ),
                        route = RouteTarget.Cellular,
                        publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                        pauseActions = pauseActions,
                        activeProxyExchanges = { 0 },
                        maxConnectionDrainTime = 30.seconds,
                        rootCommandController =
                            driverRootCommandController(rootCommandCalls) {
                                RootCommandProcessResult.Completed(exitCode = 0, stdout = "", stderr = "")
                            },
                        rootCommandTimeoutMillis = 4_000,
                        toggleDelay = 0.seconds,
                        availableNetworks = { listOf(driverNetwork("cell")) },
                        networkReturnTimeout = 60.seconds,
                        nowElapsedMillis = { now },
                        cooldown = 180.seconds,
                        rootAvailabilityTimeoutMillis = 2_000,
                    ),
                continuationExecutor = scheduledExecutor,
                continuationDelayMillis = 60_000,
            )

        try {
            val started = driver.rotateMobileData()
            now = 13_000
            runNextScheduledContinuation(scheduledExecutor)

            assertEquals(RotationTransitionDisposition.Accepted, started.disposition)
            assertEquals(RotationState.RunningEnableCommand, started.status.state)
            assertEquals(RotationState.Completed, controlPlane.currentStatus.state)
            assertEquals("198.51.100.10", controlPlane.currentStatus.oldPublicIp)
            assertEquals("198.51.100.11", controlPlane.currentStatus.newPublicIp)
            assertEquals(true, controlPlane.currentStatus.publicIpChanged)
            assertEquals(RotationOperation.MobileData, controlPlane.currentStatus.operation)
            assertTrue(!pauseActions.proxyRequestsPaused)
            assertEquals(1, pauseActions.pauseCalls)
            assertEquals(1, pauseActions.resumeCalls)
            assertEquals(
                listOf(
                    DriverRootCommandCall(RootShellCommands.mobileDataDisable(), 4_000),
                    DriverRootCommandCall(RootShellCommands.mobileDataEnable(), 4_000),
                ),
                rootCommandCalls,
            )
            assertEquals(0, scheduledExecutor.queue.size)
        } finally {
            scheduledExecutor.shutdownNow()
            assertTrue(scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `duplicate active rotation request does not enqueue duplicate continuation`() {
        val scheduledExecutor = ScheduledThreadPoolExecutor(1)
        val controlPlane = RotationControlPlane()
        val driver =
            ProxyRotationLifecycleDriver(
                coordinator =
                    ProxyRotationExecutionCoordinator(
                        controlPlane = controlPlane,
                        rootAvailabilityProbe = availableRootProbe(),
                        publicIpProbeRunner =
                            DriverSequencedPublicIpProbeRunner(
                                PublicIpProbeResult.Success(
                                    publicIp = "198.51.100.10",
                                    network = driverNetwork("cell"),
                                ),
                            ),
                        route = RouteTarget.Cellular,
                        publicIpProbeEndpoint = PublicIpProbeEndpoint(host = "ip.example"),
                        pauseActions = DriverRecordingPauseActions(),
                        nowElapsedMillis = { 12_000 },
                        cooldown = 180.seconds,
                        rootAvailabilityTimeoutMillis = 2_000,
                    ),
                continuationExecutor = scheduledExecutor,
                continuationDelayMillis = 60_000,
            )

        try {
            val first = driver.rotateMobileData()
            val duplicate = driver.rotateAirplaneMode()

            assertEquals(RotationTransitionDisposition.Accepted, first.disposition)
            assertEquals(RotationTransitionDisposition.Duplicate, duplicate.disposition)
            assertEquals(RotationState.DrainingConnections, duplicate.status.state)
            assertEquals(1, scheduledExecutor.queue.size)
        } finally {
            scheduledExecutor.shutdownNow()
            assertTrue(scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `active ignored continuation result is rescheduled instead of stranding rotation`() {
        val scheduledExecutor = ScheduledThreadPoolExecutor(1)
        val controlPlane = RotationControlPlane()
        val driver =
            ProxyRotationLifecycleDriver(
                coordinator =
                    ProxyRotationExecutionCoordinator(
                        controlPlane = controlPlane,
                        rootAvailabilityProbe = availableRootProbe(),
                        nowElapsedMillis = { 12_000 },
                        cooldown = 180.seconds,
                        rootAvailabilityTimeoutMillis = 2_000,
                    ),
                continuationExecutor = scheduledExecutor,
                continuationDelayMillis = 60_000,
            )

        try {
            val started = driver.rotateMobileData()
            runNextScheduledContinuation(scheduledExecutor)

            assertEquals(RotationTransitionDisposition.Accepted, started.disposition)
            assertEquals(RotationState.ProbingOldPublicIp, started.status.state)
            assertEquals(RotationState.ProbingOldPublicIp, controlPlane.currentStatus.state)
            assertEquals(1, scheduledExecutor.queue.size)
        } finally {
            scheduledExecutor.shutdownNow()
            assertTrue(scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `close cancels a queued continuation`() {
        val scheduledExecutor = ScheduledThreadPoolExecutor(1)
        val controlPlane = RotationControlPlane()
        val driver =
            ProxyRotationLifecycleDriver(
                coordinator =
                    ProxyRotationExecutionCoordinator(
                        controlPlane = controlPlane,
                        rootAvailabilityProbe = availableRootProbe(),
                        nowElapsedMillis = { 12_000 },
                        cooldown = 180.seconds,
                        rootAvailabilityTimeoutMillis = 2_000,
                    ),
                continuationExecutor = scheduledExecutor,
                continuationDelayMillis = 60_000,
            )

        val started = driver.rotateMobileData()
        val queued = scheduledExecutor.queue.first()
        driver.close()

        assertEquals(RotationTransitionDisposition.Accepted, started.disposition)
        assertEquals(RotationState.ProbingOldPublicIp, started.status.state)
        assertTrue((queued as java.util.concurrent.Future<*>).isCancelled)
        scheduledExecutor.shutdownNow()
        assertTrue(scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test
    fun `closed driver rejects rotation requests before mutating coordinator state`() {
        val scheduledExecutor = ScheduledThreadPoolExecutor(1)
        val controlPlane = RotationControlPlane()
        val driver =
            ProxyRotationLifecycleDriver(
                coordinator =
                    ProxyRotationExecutionCoordinator(
                        controlPlane = controlPlane,
                        rootAvailabilityProbe = availableRootProbe(),
                        nowElapsedMillis = { 12_000 },
                        cooldown = 180.seconds,
                        rootAvailabilityTimeoutMillis = 2_000,
                    ),
                continuationExecutor = scheduledExecutor,
                continuationDelayMillis = 60_000,
            )

        driver.close()
        assertFailsWith<IllegalStateException> {
            driver.rotateMobileData()
        }

        assertEquals(RotationStatus.idle(), controlPlane.currentStatus)
        assertEquals(0, scheduledExecutor.queue.size)
        scheduledExecutor.shutdownNow()
        assertTrue(scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
    }
}

private fun runNextScheduledContinuation(executor: ScheduledThreadPoolExecutor) {
    val task = executor.queue.firstOrNull() ?: error("Expected a scheduled continuation")
    executor.queue.remove(task)
    task.run()
}

private class DriverSequencedPublicIpProbeRunner(
    private vararg val results: PublicIpProbeResult,
) : PublicIpProbeRunner {
    private var nextResultIndex = 0

    override suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult = results[nextResultIndex++]
}

private data class DriverRootCommandCall(
    val command: RootShellCommand,
    val timeoutMillis: Long,
)

private class DriverRecordingPauseActions : ProxyRotationPauseActions {
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

private fun driverNetwork(id: String): NetworkDescriptor = NetworkDescriptor(
    id = id,
    category = NetworkCategory.Cellular,
    displayName = id,
    isAvailable = true,
)

private fun availableRootProbe(): RotationRootAvailabilityProbe = RootAvailabilityChecker(
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
)

private fun driverRootCommandController(
    calls: MutableList<DriverRootCommandCall>,
    processResult: (RootShellCommand) -> RootCommandProcessResult,
): RotationRootCommandController {
    val executor =
        RootCommandExecutor(
            processExecutor =
                RootCommandProcessExecutor { command, timeoutMillis ->
                    calls += DriverRootCommandCall(command, timeoutMillis)
                    processResult(command)
                },
        )
    return RotationRootCommandController(
        mobileDataController = MobileDataRootController(executor),
        airplaneModeController = AirplaneModeRootController(executor),
    )
}
