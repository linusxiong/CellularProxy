package com.cellularproxy.app.service

import com.cellularproxy.network.PublicIpProbeEndpoint
import com.cellularproxy.network.PublicIpProbeRunner
import com.cellularproxy.network.RotationPublicIpProbeAdvanceResult
import com.cellularproxy.network.RotationPublicIpProbeController
import com.cellularproxy.network.RotationPublicIpProbeCoordinator
import com.cellularproxy.proxy.server.ProxyRotationConnectionDrainAdvanceResult
import com.cellularproxy.proxy.server.ProxyRotationConnectionDrainController
import com.cellularproxy.proxy.server.ProxyRotationConnectionDrainCoordinator
import com.cellularproxy.proxy.server.ProxyRotationPauseActions
import com.cellularproxy.proxy.server.ProxyRotationPauseAdvanceResult
import com.cellularproxy.proxy.server.ProxyRotationPauseCoordinator
import com.cellularproxy.root.RotationRootAvailabilityAdvanceResult
import com.cellularproxy.root.RotationRootAvailabilityController
import com.cellularproxy.root.RotationRootAvailabilityCoordinator
import com.cellularproxy.root.RotationRootAvailabilityProbe
import com.cellularproxy.root.RotationRootCommandAdvanceResult
import com.cellularproxy.root.RotationRootCommandController
import com.cellularproxy.root.RotationRootCommandCoordinator
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Owns the first production-safe rotation execution boundary: cooldown, root
 * availability, and opt-in old-public-IP probing, pause, drain, and root
 * command execution. Production wiring can leave later phases absent until their
 * orchestrators are ready.
 */
class ProxyRotationExecutionCoordinator(
    private val controlPlane: RotationControlPlane,
    rootAvailabilityProbe: RotationRootAvailabilityProbe,
    private val nowElapsedMillis: () -> Long,
    private val cooldown: Duration,
    private val rootAvailabilityTimeoutMillis: Long,
    publicIpProbeRunner: PublicIpProbeRunner? = null,
    private val route: RouteTarget = RouteTarget.Automatic,
    private val publicIpProbeEndpoint: PublicIpProbeEndpoint? = null,
    pauseActions: ProxyRotationPauseActions? = null,
    activeProxyExchanges: (() -> Long)? = null,
    private val maxConnectionDrainTime: Duration? = null,
    rootCommandController: RotationRootCommandController? = null,
    private val rootCommandTimeoutMillis: Long? = null,
    private val secrets: () -> LogRedactionSecrets = { LogRedactionSecrets() },
) {
    private val rootAvailabilityCoordinator =
        RotationRootAvailabilityCoordinator(
            availabilityController = RotationRootAvailabilityController(rootAvailabilityProbe),
            controlPlane = controlPlane,
        )
    private val publicIpProbeCoordinator =
        publicIpProbeRunner?.let { runner ->
            RotationPublicIpProbeCoordinator(
                probeController = RotationPublicIpProbeController(runner),
                controlPlane = controlPlane,
            )
        }
    private val pauseCoordinator =
        pauseActions?.let { actions ->
            ProxyRotationPauseCoordinator(
                pauseController = actions,
                controlPlane = controlPlane,
            )
        }
    private val connectionDrainCoordinator =
        activeProxyExchanges?.let { countActiveProxyExchanges ->
            ProxyRotationConnectionDrainCoordinator(
                drainController = ProxyRotationConnectionDrainController(countActiveProxyExchanges),
                controlPlane = controlPlane,
            )
        }
    private val rootCommandCoordinator =
        rootCommandController?.let { controller ->
            RotationRootCommandCoordinator(
                commandController = controller,
                controlPlane = controlPlane,
            )
        }
    private var drainStartedElapsedMillis: Long? = null

    init {
        require(rootAvailabilityTimeoutMillis > 0) {
            "Rotation root availability timeout must be positive"
        }
        require((publicIpProbeRunner == null) == (publicIpProbeEndpoint == null)) {
            "Rotation public IP probe runner and endpoint must be configured together"
        }
        require((activeProxyExchanges == null) == (maxConnectionDrainTime == null)) {
            "Rotation active proxy exchange counter and maximum drain time must be configured together"
        }
        require(activeProxyExchanges == null || pauseActions != null) {
            "Rotation connection drain requires pause actions"
        }
        require(maxConnectionDrainTime == null || !maxConnectionDrainTime.isNegative()) {
            "Rotation maximum connection drain time must not be negative"
        }
        require((rootCommandController == null) == (rootCommandTimeoutMillis == null)) {
            "Rotation root command controller and timeout must be configured together"
        }
        require(rootCommandController == null || pauseActions != null) {
            "Rotation root command execution requires pause actions"
        }
        require(rootCommandController == null || activeProxyExchanges != null) {
            "Rotation root command execution requires connection drain configuration"
        }
        require(rootCommandTimeoutMillis == null || rootCommandTimeoutMillis > 0) {
            "Rotation root command timeout must be positive"
        }
    }

    suspend fun rotateMobileData(): RotationTransitionResult = rotate(RotationOperation.MobileData)

    suspend fun rotateAirplaneMode(): RotationTransitionResult = rotate(RotationOperation.AirplaneMode)

    fun advanceConnectionDrain(): RotationTransitionResult {
        val now = nowElapsedMillis()
        return continueWithConnectionDrainCoordinatorIfConfigured(
            pauseTransition = controlPlane.currentStatus.asIgnoredTransition(),
            nowElapsedMillis = now,
            redactionSecrets = rootCommandCoordinator?.let { secrets() },
        )
    }

    fun advanceRootCommand(): RotationTransitionResult = continueWithRootCommandCoordinatorIfConfigured(
        commandTransition = controlPlane.currentStatus.asIgnoredTransition(),
        nowElapsedMillis = nowElapsedMillis(),
        redactionSecrets = rootCommandCoordinator?.let { secrets() },
    )

    private suspend fun rotate(operation: RotationOperation): RotationTransitionResult {
        val now = nowElapsedMillis()
        val redactionSecrets = secrets()
        val startResult =
            controlPlane.requestStart(
                operation = operation,
                nowElapsedMillis = now,
                cooldown = cooldown,
            )
        val cooldownTransition = startResult.cooldownTransition
        if (cooldownTransition == null) {
            return startResult.startTransition
        }
        if (
            cooldownTransition.disposition != RotationTransitionDisposition.Accepted ||
            !cooldownTransition.status.isActive
        ) {
            return cooldownTransition
        }

        val expectedSnapshot = controlPlane.snapshot()
        val rootResult =
            try {
                rootAvailabilityCoordinator.checkRootAvailability(
                    expectedSnapshot = expectedSnapshot,
                    timeoutMillis = rootAvailabilityTimeoutMillis,
                    nowElapsedMillis = now,
                    secrets = redactionSecrets,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                synchronized(controlPlane) {
                    val actualSnapshot = controlPlane.snapshot()
                    if (actualSnapshot != expectedSnapshot) {
                        return actualSnapshot.status.asIgnoredTransition()
                    }
                    return controlPlane
                        .applyProgress(
                            event = RotationEvent.RootUnavailable,
                            nowElapsedMillis = now,
                        ).transition
                }
            }

        return when (rootResult) {
            is RotationRootAvailabilityAdvanceResult.Applied ->
                continueWithOldPublicIpProbeIfConfigured(
                    rootTransition = rootResult.progress.transition,
                    nowElapsedMillis = now,
                    redactionSecrets = redactionSecrets,
                )
            is RotationRootAvailabilityAdvanceResult.NoAction ->
                rootResult.snapshot.status.asIgnoredTransition()
            is RotationRootAvailabilityAdvanceResult.Stale ->
                rootResult.actualSnapshot.status.asIgnoredTransition()
        }
    }

    private suspend fun continueWithOldPublicIpProbeIfConfigured(
        rootTransition: RotationTransitionResult,
        nowElapsedMillis: Long,
        redactionSecrets: LogRedactionSecrets,
    ): RotationTransitionResult {
        val coordinator = publicIpProbeCoordinator ?: return rootTransition
        val endpoint = requireNotNull(publicIpProbeEndpoint)
        if (rootTransition.status.state != RotationState.ProbingOldPublicIp) {
            return rootTransition
        }

        val expectedSnapshot = controlPlane.snapshot()
        val probeResult =
            try {
                coordinator.probeOldPublicIp(
                    expectedSnapshot = expectedSnapshot,
                    route = route,
                    endpoint = endpoint,
                    nowElapsedMillis = nowElapsedMillis,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                synchronized(controlPlane) {
                    val actualSnapshot = controlPlane.snapshot()
                    if (actualSnapshot != expectedSnapshot) {
                        return actualSnapshot.status.asIgnoredTransition()
                    }
                    return controlPlane
                        .applyProgress(
                            event = RotationEvent.OldPublicIpProbeFailed,
                            nowElapsedMillis = nowElapsedMillis,
                        ).transition
                }
            }

        return when (probeResult) {
            is RotationPublicIpProbeAdvanceResult.Applied ->
                continueWithPauseCoordinatorIfConfigured(
                    oldPublicIpTransition = probeResult.progress.transition,
                    redactionSecrets = redactionSecrets,
                )
            is RotationPublicIpProbeAdvanceResult.NoAction ->
                probeResult.snapshot.status.asIgnoredTransition()
            is RotationPublicIpProbeAdvanceResult.Stale ->
                probeResult.actualSnapshot.status.asIgnoredTransition()
        }
    }

    private fun continueWithPauseCoordinatorIfConfigured(
        oldPublicIpTransition: RotationTransitionResult,
        redactionSecrets: LogRedactionSecrets,
    ): RotationTransitionResult {
        val coordinator = pauseCoordinator ?: return oldPublicIpTransition
        if (oldPublicIpTransition.status.state != RotationState.PausingNewRequests) {
            return oldPublicIpTransition
        }

        val pauseElapsedMillis = nowElapsedMillis()
        return when (val pauseResult = coordinator.advance(pauseElapsedMillis)) {
            is ProxyRotationPauseAdvanceResult.Applied ->
                continueWithConnectionDrainCoordinatorIfConfigured(
                    pauseTransition = pauseResult.progress.transition,
                    nowElapsedMillis = pauseElapsedMillis,
                    redactionSecrets = redactionSecrets,
                )
            is ProxyRotationPauseAdvanceResult.NoAction -> pauseResult.snapshot.status.asIgnoredTransition()
        }
    }

    private fun continueWithConnectionDrainCoordinatorIfConfigured(
        pauseTransition: RotationTransitionResult,
        nowElapsedMillis: Long,
        redactionSecrets: LogRedactionSecrets?,
    ): RotationTransitionResult {
        val coordinator = connectionDrainCoordinator ?: return pauseTransition
        val drainTransition =
            synchronized(controlPlane) {
                if (pauseTransition.status.state != RotationState.DrainingConnections) {
                    drainStartedElapsedMillis = null
                    return pauseTransition
                }
                val drainStarted =
                    drainStartedElapsedMillis ?: nowElapsedMillis.also {
                        drainStartedElapsedMillis = it
                    }

                when (
                    val drainResult =
                        coordinator.advance(
                            drainStartedElapsedMillis = drainStarted,
                            nowElapsedMillis = nowElapsedMillis,
                            maxDrainTime = requireNotNull(maxConnectionDrainTime),
                        )
                ) {
                    is ProxyRotationConnectionDrainAdvanceResult.Applied -> {
                        drainStartedElapsedMillis = null
                        drainResult.progress.transition
                    }
                    is ProxyRotationConnectionDrainAdvanceResult.Waiting -> pauseTransition
                    is ProxyRotationConnectionDrainAdvanceResult.NoAction -> {
                        drainStartedElapsedMillis = null
                        drainResult.snapshot.status.asIgnoredTransition()
                    }
                }
            }

        return continueWithRootCommandCoordinatorIfConfigured(
            commandTransition = drainTransition,
            nowElapsedMillis = nowElapsedMillis,
            redactionSecrets = redactionSecrets,
        )
    }

    private fun continueWithRootCommandCoordinatorIfConfigured(
        commandTransition: RotationTransitionResult,
        nowElapsedMillis: Long,
        redactionSecrets: LogRedactionSecrets?,
    ): RotationTransitionResult {
        val coordinator = rootCommandCoordinator ?: return commandTransition
        if (
            commandTransition.status.state != RotationState.RunningDisableCommand &&
            commandTransition.status.state != RotationState.RunningEnableCommand
        ) {
            return commandTransition
        }

        return when (
            val commandResult =
                coordinator.runCurrentCommand(
                    timeoutMillis = requireNotNull(rootCommandTimeoutMillis),
                    nowElapsedMillis = nowElapsedMillis,
                    secrets = requireNotNull(redactionSecrets),
                )
        ) {
            is RotationRootCommandAdvanceResult.Applied ->
                continueWithResumeIfNeeded(commandResult.progress.transition, nowElapsedMillis)
            is RotationRootCommandAdvanceResult.NoAction ->
                continueWithResumeIfNeeded(commandResult.snapshot.status.asIgnoredTransition(), nowElapsedMillis)
            is RotationRootCommandAdvanceResult.Stale ->
                continueWithResumeIfNeeded(commandResult.snapshot.status.asIgnoredTransition(), nowElapsedMillis)
        }
    }

    private fun continueWithResumeIfNeeded(
        transition: RotationTransitionResult,
        nowElapsedMillis: Long,
    ): RotationTransitionResult {
        if (transition.status.state != RotationState.ResumingProxyRequests) {
            return transition
        }
        val coordinator =
            requireNotNull(pauseCoordinator) {
                "Rotation root command failure requires pause actions to resume proxy requests"
            }
        return when (val pauseResult = coordinator.advance(nowElapsedMillis)) {
            is ProxyRotationPauseAdvanceResult.Applied -> pauseResult.progress.transition
            is ProxyRotationPauseAdvanceResult.NoAction -> pauseResult.snapshot.status.asIgnoredTransition()
        }
    }
}

private fun RotationStatus.asIgnoredTransition(): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Ignored,
    status = this,
)
