package com.cellularproxy.shared.rotation

import java.util.concurrent.atomic.AtomicLong

class TerminalRotationTimestampTracker(
    initialLastTerminalElapsedMillis: Long? = null,
) {
    private val lastTerminalElapsedMillisReference = AtomicLong(
        initialLastTerminalElapsedMillis ?: NO_TERMINAL_ROTATION_RECORDED,
    )

    val lastTerminalElapsedMillis: Long?
        get() = lastTerminalElapsedMillisReference.get().takeUnless {
            it == NO_TERMINAL_ROTATION_RECORDED
        }

    init {
        require(initialLastTerminalElapsedMillis == null || initialLastTerminalElapsedMillis >= 0) {
            "Initial last terminal rotation elapsed millis must not be negative"
        }
    }

    fun observe(
        transition: RotationTransitionResult,
        nowElapsedMillis: Long,
    ): TerminalRotationTimestampObservation {
        require(nowElapsedMillis >= 0) { "Observation elapsed millis must not be negative" }

        if (!transition.accepted) {
            return TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.TransitionNotAccepted,
            )
        }

        return when (transition.status.state) {
            RotationState.Completed -> record(nowElapsedMillis)
            RotationState.Failed -> {
                if (transition.status.failureReason == RotationFailureReason.CooldownActive) {
                    TerminalRotationTimestampObservation.NotRecorded(
                        TerminalRotationTimestampNotRecordedReason.CooldownRejected,
                    )
                } else {
                    record(nowElapsedMillis)
                }
            }
            else -> TerminalRotationTimestampObservation.NotRecorded(
                TerminalRotationTimestampNotRecordedReason.NotTerminal,
            )
        }
    }

    private fun record(nowElapsedMillis: Long): TerminalRotationTimestampObservation {
        while (true) {
            val current = lastTerminalElapsedMillisReference.get()
            if (current != NO_TERMINAL_ROTATION_RECORDED && nowElapsedMillis < current) {
                return TerminalRotationTimestampObservation.NotRecorded(
                    TerminalRotationTimestampNotRecordedReason.StaleTerminalTimestamp,
                )
            }
            if (lastTerminalElapsedMillisReference.compareAndSet(current, nowElapsedMillis)) {
                return TerminalRotationTimestampObservation.Recorded(
                    terminalElapsedMillis = nowElapsedMillis,
                )
            }
        }
    }
}

sealed interface TerminalRotationTimestampObservation {
    data class Recorded(
        val terminalElapsedMillis: Long,
    ) : TerminalRotationTimestampObservation {
        init {
            require(terminalElapsedMillis >= 0) {
                "Recorded terminal rotation elapsed millis must not be negative"
            }
        }
    }

    data class NotRecorded(
        val reason: TerminalRotationTimestampNotRecordedReason,
    ) : TerminalRotationTimestampObservation
}

enum class TerminalRotationTimestampNotRecordedReason {
    TransitionNotAccepted,
    NotTerminal,
    CooldownRejected,
    StaleTerminalTimestamp,
}

private const val NO_TERMINAL_ROTATION_RECORDED = -1L
