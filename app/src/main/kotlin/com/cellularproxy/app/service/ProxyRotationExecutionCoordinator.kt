package com.cellularproxy.app.service

import com.cellularproxy.network.PublicIpProbeEndpoint
import com.cellularproxy.network.PublicIpProbeRunner
import com.cellularproxy.network.RotationPublicIpProbeAdvanceResult
import com.cellularproxy.network.RotationPublicIpProbeController
import com.cellularproxy.network.RotationPublicIpProbeCoordinator
import com.cellularproxy.root.RotationRootAvailabilityAdvanceResult
import com.cellularproxy.root.RotationRootAvailabilityController
import com.cellularproxy.root.RotationRootAvailabilityCoordinator
import com.cellularproxy.root.RotationRootAvailabilityProbe
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
 * availability, and optional old-public-IP probing. It intentionally stops
 * before pausing requests unless follow-on phase orchestrators are present.
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

    init {
        require(rootAvailabilityTimeoutMillis > 0) {
            "Rotation root availability timeout must be positive"
        }
        require((publicIpProbeRunner == null) == (publicIpProbeEndpoint == null)) {
            "Rotation public IP probe runner and endpoint must be configured together"
        }
    }

    suspend fun rotateMobileData(): RotationTransitionResult = rotate(RotationOperation.MobileData)

    suspend fun rotateAirplaneMode(): RotationTransitionResult = rotate(RotationOperation.AirplaneMode)

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
            is RotationPublicIpProbeAdvanceResult.Applied -> probeResult.progress.transition
            is RotationPublicIpProbeAdvanceResult.NoAction ->
                probeResult.snapshot.status.asIgnoredTransition()
            is RotationPublicIpProbeAdvanceResult.Stale ->
                probeResult.actualSnapshot.status.asIgnoredTransition()
        }
    }
}

private fun RotationStatus.asIgnoredTransition(): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Ignored,
    status = this,
)
