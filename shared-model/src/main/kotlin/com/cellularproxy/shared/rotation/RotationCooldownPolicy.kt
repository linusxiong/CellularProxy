package com.cellularproxy.shared.rotation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object RotationCooldownPolicy {
    fun evaluate(
        lastTerminalRotationElapsedMillis: Long?,
        nowElapsedMillis: Long,
        cooldown: Duration,
    ): RotationCooldownDecision {
        require(lastTerminalRotationElapsedMillis == null || lastTerminalRotationElapsedMillis >= 0) {
            "Last terminal rotation elapsed millis must not be negative"
        }
        require(nowElapsedMillis >= 0) { "Current elapsed millis must not be negative" }
        require(!cooldown.isNegative()) { "Rotation cooldown must not be negative" }

        if (lastTerminalRotationElapsedMillis == null || cooldown == Duration.ZERO) {
            return RotationCooldownDecision.Passed
        }

        val elapsed = (nowElapsedMillis - lastTerminalRotationElapsedMillis).milliseconds
        val remaining = cooldown - elapsed

        return if (remaining <= Duration.ZERO) {
            RotationCooldownDecision.Passed
        } else {
            RotationCooldownDecision.Rejected(remainingCooldown = remaining)
        }
    }
}

sealed interface RotationCooldownDecision {
    val event: RotationEvent

    data object Passed : RotationCooldownDecision {
        override val event: RotationEvent = RotationEvent.CooldownPassed
    }

    data class Rejected(
        val remainingCooldown: Duration,
    ) : RotationCooldownDecision {
        init {
            require(remainingCooldown > Duration.ZERO) {
                "Rejected rotation cooldown requires positive remaining cooldown"
            }
        }

        override val event: RotationEvent = RotationEvent.CooldownRejected
    }
}
