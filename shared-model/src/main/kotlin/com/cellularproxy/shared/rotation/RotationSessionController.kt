package com.cellularproxy.shared.rotation

import java.util.concurrent.atomic.AtomicReference

class RotationSessionController(
    initialStatus: RotationStatus = RotationStatus.idle(),
) {
    private val status = AtomicReference(initialStatus)

    val currentStatus: RotationStatus
        get() = status.get()

    fun requestStart(operation: RotationOperation): RotationTransitionResult = apply(RotationEvent.StartRequested(operation))

    fun apply(event: RotationEvent): RotationTransitionResult {
        while (true) {
            val current = status.get()
            val result = RotationStateMachine.transition(current, event)
            if (!result.accepted) {
                return result
            }
            if (status.compareAndSet(current, result.status)) {
                return result
            }
        }
    }
}
