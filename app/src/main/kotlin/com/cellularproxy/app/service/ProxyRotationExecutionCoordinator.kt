package com.cellularproxy.app.service

import com.cellularproxy.root.RotationRootAvailabilityAdvanceResult
import com.cellularproxy.root.RotationRootAvailabilityController
import com.cellularproxy.root.RotationRootAvailabilityCoordinator
import com.cellularproxy.root.RotationRootAvailabilityProbe
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlin.time.Duration

/**
 * Owns the first production-safe rotation execution boundary: cooldown and
 * root availability. It intentionally stops at the old-public-IP probe phase;
 * production wiring should only use it with the follow-on phase orchestrators.
 */
class ProxyRotationExecutionCoordinator(
    private val controlPlane: RotationControlPlane,
    rootAvailabilityProbe: RotationRootAvailabilityProbe,
    private val nowElapsedMillis: () -> Long,
    private val cooldown: Duration,
    private val rootAvailabilityTimeoutMillis: Long,
    private val secrets: () -> LogRedactionSecrets = { LogRedactionSecrets() },
) {
    private val rootAvailabilityCoordinator =
        RotationRootAvailabilityCoordinator(
            availabilityController = RotationRootAvailabilityController(rootAvailabilityProbe),
            controlPlane = controlPlane,
        )

    init {
        require(rootAvailabilityTimeoutMillis > 0) {
            "Rotation root availability timeout must be positive"
        }
    }

    fun rotateMobileData(): RotationTransitionResult = rotate(RotationOperation.MobileData)

    fun rotateAirplaneMode(): RotationTransitionResult = rotate(RotationOperation.AirplaneMode)

    private fun rotate(operation: RotationOperation): RotationTransitionResult {
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
            is RotationRootAvailabilityAdvanceResult.Applied -> rootResult.progress.transition
            is RotationRootAvailabilityAdvanceResult.NoAction ->
                rootResult.snapshot.status.asIgnoredTransition()
            is RotationRootAvailabilityAdvanceResult.Stale ->
                rootResult.actualSnapshot.status.asIgnoredTransition()
        }
    }
}

private fun RotationStatus.asIgnoredTransition(): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Ignored,
    status = this,
)
