package com.cellularproxy.proxy.server

import com.cellularproxy.shared.rotation.RotationConnectionDrainDecision
import com.cellularproxy.shared.rotation.RotationConnectionDrainPolicy
import kotlin.time.Duration

class ProxyRotationConnectionDrainController(
    private val activeProxyExchanges: () -> Long,
) {
    fun evaluate(
        drainStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        maxDrainTime: Duration,
    ): RotationConnectionDrainDecision {
        val activeExchanges = activeProxyExchanges()
        check(activeExchanges >= 0) {
            "Active proxy exchange count must not be negative"
        }

        return RotationConnectionDrainPolicy.evaluate(
            activeConnections = activeExchanges.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            drainStartedElapsedMillis = drainStartedElapsedMillis,
            nowElapsedMillis = nowElapsedMillis,
            maxDrainTime = maxDrainTime,
        )
    }
}
