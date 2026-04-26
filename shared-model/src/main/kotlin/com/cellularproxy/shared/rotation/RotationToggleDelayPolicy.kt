package com.cellularproxy.shared.rotation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object RotationToggleDelayPolicy {
    fun evaluate(
        delayStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        toggleDelay: Duration,
    ): RotationToggleDelayDecision {
        require(delayStartedElapsedMillis >= 0) {
            "Rotation toggle delay start elapsed millis must not be negative"
        }
        require(nowElapsedMillis >= 0) {
            "Current elapsed millis must not be negative"
        }
        require(!toggleDelay.isNegative()) {
            "Rotation toggle delay must not be negative"
        }

        val elapsed = (nowElapsedMillis - delayStartedElapsedMillis).milliseconds
        val remainingDelay = toggleDelay - elapsed

        return if (remainingDelay <= Duration.ZERO) {
            RotationToggleDelayDecision.Elapsed
        } else {
            RotationToggleDelayDecision.Waiting(remainingDelay = remainingDelay)
        }
    }
}

sealed interface RotationToggleDelayDecision {
    val event: RotationEvent?

    data object Elapsed : RotationToggleDelayDecision {
        override val event: RotationEvent = RotationEvent.ToggleDelayElapsed
    }

    data class Waiting(
        val remainingDelay: Duration,
    ) : RotationToggleDelayDecision {
        init {
            require(remainingDelay > Duration.ZERO) {
                "Waiting for rotation toggle delay requires positive remaining delay"
            }
        }

        override val event: RotationEvent? = null
    }
}
