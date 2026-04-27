package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.network.RouteSelector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object RotationNetworkReturnPolicy {
    fun evaluate(
        routeTarget: RouteTarget,
        networks: List<NetworkDescriptor>,
        waitStartedElapsedMillis: Long,
        nowElapsedMillis: Long,
        networkReturnTimeout: Duration,
    ): RotationNetworkReturnDecision {
        require(waitStartedElapsedMillis >= 0) {
            "Network return wait start elapsed millis must not be negative"
        }
        require(nowElapsedMillis >= 0) { "Current elapsed millis must not be negative" }
        require(!networkReturnTimeout.isNegative()) { "Network return timeout must not be negative" }

        val selectedNetwork = RouteSelector.candidatesFor(routeTarget, networks).firstOrNull()
        if (selectedNetwork != null) {
            return RotationNetworkReturnDecision.Returned(
                routeTarget = routeTarget,
                selectedNetwork = selectedNetwork,
            )
        }

        val elapsed = (nowElapsedMillis - waitStartedElapsedMillis).milliseconds
        val remaining = networkReturnTimeout - elapsed

        return if (remaining <= Duration.ZERO) {
            RotationNetworkReturnDecision.TimedOut(routeTarget = routeTarget)
        } else {
            RotationNetworkReturnDecision.Waiting(
                routeTarget = routeTarget,
                remainingReturnTime = remaining,
            )
        }
    }
}

sealed interface RotationNetworkReturnDecision {
    val event: RotationEvent?

    data class Returned(
        val routeTarget: RouteTarget,
        val selectedNetwork: NetworkDescriptor,
    ) : RotationNetworkReturnDecision {
        init {
            require(selectedNetwork.isAvailable) {
                "Returned network must be available"
            }
            require(routeTarget == RouteTarget.Automatic || selectedNetwork.category == routeTarget.networkCategory) {
                "Returned network category must match the selected route target"
            }
        }

        override val event: RotationEvent = RotationEvent.NetworkReturned
    }

    data class Waiting(
        val routeTarget: RouteTarget,
        val remainingReturnTime: Duration,
    ) : RotationNetworkReturnDecision {
        init {
            require(remainingReturnTime > Duration.ZERO) {
                "Waiting for network return requires positive remaining time"
            }
        }

        override val event: RotationEvent? = null
    }

    data class TimedOut(
        val routeTarget: RouteTarget,
    ) : RotationNetworkReturnDecision {
        override val event: RotationEvent = RotationEvent.NetworkReturnTimedOut
    }
}

private val RouteTarget.networkCategory: NetworkCategory
    get() =
        when (this) {
            RouteTarget.WiFi -> NetworkCategory.WiFi
            RouteTarget.Cellular -> NetworkCategory.Cellular
            RouteTarget.Vpn -> NetworkCategory.Vpn
            RouteTarget.Automatic -> error("Automatic route target does not map to one category")
        }
