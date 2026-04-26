package com.cellularproxy.shared.rotation

import kotlin.time.Duration

class RotationStartGate(
    private val sessionController: RotationSessionController,
    private val terminalTimestampTracker: TerminalRotationTimestampTracker,
) {
    fun requestStart(
        operation: RotationOperation,
        nowElapsedMillis: Long,
        cooldown: Duration,
    ): RotationStartGateResult {
        require(nowElapsedMillis >= 0) { "Current elapsed millis must not be negative" }
        require(!cooldown.isNegative()) { "Rotation cooldown must not be negative" }

        val startTransition = sessionController.requestStart(operation)
        if (!startTransition.accepted) {
            return RotationStartGateResult(
                startTransition = startTransition,
                cooldownDecision = null,
                cooldownTransition = null,
                terminalTimestampObservation = null,
            )
        }

        val cooldownDecision = RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = terminalTimestampTracker.lastTerminalElapsedMillis,
            nowElapsedMillis = nowElapsedMillis,
            cooldown = cooldown,
        )
        val cooldownTransition = sessionController.apply(cooldownDecision.event)
        val terminalTimestampObservation = terminalTimestampTracker.observe(
            transition = cooldownTransition,
            nowElapsedMillis = nowElapsedMillis,
        )

        return RotationStartGateResult(
            startTransition = startTransition,
            cooldownDecision = cooldownDecision,
            cooldownTransition = cooldownTransition,
            terminalTimestampObservation = terminalTimestampObservation,
        )
    }
}

data class RotationStartGateResult(
    val startTransition: RotationTransitionResult,
    val cooldownDecision: RotationCooldownDecision?,
    val cooldownTransition: RotationTransitionResult?,
    val terminalTimestampObservation: TerminalRotationTimestampObservation?,
) {
    val status: RotationStatus
        get() = cooldownTransition?.status ?: startTransition.status
}
