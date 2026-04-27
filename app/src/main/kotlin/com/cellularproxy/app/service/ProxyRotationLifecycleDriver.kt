package com.cellularproxy.app.service

import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns asynchronous continuation for a rotation after a management request has
 * accepted it. The coordinator owns phase correctness; this driver only decides
 * when to ask for the next phase.
 */
class ProxyRotationLifecycleDriver(
    private val coordinator: ProxyRotationExecutionCoordinator,
    private val continuationExecutor: ScheduledExecutorService,
    private val continuationDelayMillis: Long = DEFAULT_ROTATION_CONTINUATION_DELAY_MILLIS,
) : RuntimeRotationRequestHandler {
    private val lock = Any()
    private var closed = false
    private var continuationScheduled = false
    private var scheduledContinuation: ScheduledFuture<*>? = null

    init {
        require(continuationDelayMillis > 0) {
            "Rotation continuation delay must be positive"
        }
    }

    override fun rotateMobileData(): RotationTransitionResult = synchronized(lock) {
        check(!closed) { "Rotation lifecycle driver is closed" }
        runBlocking {
            coordinator.rotateMobileData()
        }.also(::scheduleContinuationIfNeededLocked)
    }

    override fun rotateAirplaneMode(): RotationTransitionResult = synchronized(lock) {
        check(!closed) { "Rotation lifecycle driver is closed" }
        runBlocking {
            coordinator.rotateAirplaneMode()
        }.also(::scheduleContinuationIfNeededLocked)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            continuationScheduled = false
            scheduledContinuation?.cancel(false)
            scheduledContinuation = null
        }
    }

    private fun runScheduledContinuation() {
        synchronized(lock) {
            continuationScheduled = false
            scheduledContinuation = null
            if (closed) {
                return
            }
            runBlocking {
                coordinator.advanceCurrentRotation()
            }.also(::scheduleContinuationIfNeededLocked)
        }
    }

    private fun scheduleContinuationIfNeededLocked(result: RotationTransitionResult) {
        if (closed || !result.requiresContinuation()) {
            return
        }
        if (continuationScheduled) {
            return
        }

        scheduledContinuation =
            continuationExecutor.schedule(
                ::runScheduledContinuation,
                continuationDelayMillis,
                TimeUnit.MILLISECONDS,
            )
        continuationScheduled = true
    }
}

private fun RotationTransitionResult.requiresContinuation(): Boolean = status.isActive

private const val DEFAULT_ROTATION_CONTINUATION_DELAY_MILLIS = 1_000L
