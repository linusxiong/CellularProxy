package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationEvent

fun interface RotationRootAvailabilityProbe {
    fun check(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets,
    ): RootAvailabilityCheckResult
}

class RotationRootAvailabilityController(
    private val probe: RotationRootAvailabilityProbe,
) {
    fun checkRoot(
        timeoutMillis: Long,
        secrets: LogRedactionSecrets = LogRedactionSecrets(),
    ): RotationRootAvailabilityDecision {
        val result = probe.check(timeoutMillis = timeoutMillis, secrets = secrets)

        return when (result.status) {
            RootAvailabilityStatus.Available ->
                RotationRootAvailabilityDecision.Available(result)
            RootAvailabilityStatus.Unavailable,
            RootAvailabilityStatus.Unknown,
            -> RotationRootAvailabilityDecision.Unavailable(result)
        }
    }
}

sealed interface RotationRootAvailabilityDecision {
    val result: RootAvailabilityCheckResult
    val event: RotationEvent
    val failureReason: RootAvailabilityCheckFailure?

    data class Available(
        override val result: RootAvailabilityCheckResult,
    ) : RotationRootAvailabilityDecision {
        init {
            require(result.status == RootAvailabilityStatus.Available) {
                "Available rotation root check requires available root status"
            }
        }

        override val event: RotationEvent = RotationEvent.RootAvailable
        override val failureReason: RootAvailabilityCheckFailure? = null
    }

    data class Unavailable(
        override val result: RootAvailabilityCheckResult,
    ) : RotationRootAvailabilityDecision {
        init {
            require(result.status != RootAvailabilityStatus.Available) {
                "Unavailable rotation root check requires non-available root status"
            }
        }

        override val failureReason: RootAvailabilityCheckFailure?
            get() = result.failureReason

        override val event: RotationEvent = RotationEvent.RootUnavailable
    }
}
