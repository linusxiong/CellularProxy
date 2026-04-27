package com.cellularproxy.shared.rotation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object RotationConnectionDrainPolicy {
    fun evaluate(
        activeConnections: Int,
        drainStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        maxDrainTime: Duration,
    ): RotationConnectionDrainDecision {
        require(activeConnections >= 0) { "Active connection count must not be negative" }
        require(drainStartedElapsedMillis >= 0) { "Drain start elapsed millis must not be negative" }
        require(nowElapsedMillis >= 0) { "Current elapsed millis must not be negative" }
        require(!maxDrainTime.isNegative()) { "Maximum drain time must not be negative" }

        if (activeConnections == 0) {
            return RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.NoActiveConnections,
                activeConnections = activeConnections,
            )
        }

        val elapsed = (nowElapsedMillis - drainStartedElapsedMillis).milliseconds
        val remaining = maxDrainTime - elapsed

        return if (remaining <= Duration.ZERO) {
            RotationConnectionDrainDecision.Drained(
                reason = RotationConnectionDrainReason.MaxDrainTimeElapsed,
                activeConnections = activeConnections,
            )
        } else {
            RotationConnectionDrainDecision.Waiting(
                activeConnections = activeConnections,
                remainingDrainTime = remaining,
            )
        }
    }
}

sealed interface RotationConnectionDrainDecision {
    val event: RotationEvent?

    data class Drained(
        val reason: RotationConnectionDrainReason,
        val activeConnections: Int,
    ) : RotationConnectionDrainDecision {
        init {
            require(activeConnections >= 0) {
                "Drained connection count must not be negative"
            }
            when (reason) {
                RotationConnectionDrainReason.NoActiveConnections ->
                    require(activeConnections == 0) {
                        "No-active-connections drain requires zero active connections"
                    }
                RotationConnectionDrainReason.MaxDrainTimeElapsed ->
                    require(activeConnections > 0) {
                        "Max-drain-time drain requires active connections to remain"
                    }
            }
        }

        override val event: RotationEvent = RotationEvent.ConnectionsDrained
    }

    data class Waiting(
        val activeConnections: Int,
        val remainingDrainTime: Duration,
    ) : RotationConnectionDrainDecision {
        init {
            require(activeConnections > 0) {
                "Waiting for connection drain requires active connections"
            }
            require(remainingDrainTime > Duration.ZERO) {
                "Waiting for connection drain requires positive remaining time"
            }
        }

        override val event: RotationEvent? = null
    }
}

enum class RotationConnectionDrainReason {
    NoActiveConnections,
    MaxDrainTimeElapsed,
}
