package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RotationNetworkReturnPolicyTest {
    @Test
    fun `rotation network return succeeds when selected explicit route is available`() {
        val cellular =
            network(
                id = "cell-1",
                category = NetworkCategory.Cellular,
                isAvailable = true,
            )

        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks =
                    listOf(
                        network(id = "wifi-1", category = NetworkCategory.WiFi, isAvailable = true),
                        cellular,
                    ),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_250,
                networkReturnTimeout = 60.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.Returned(
                routeTarget = RouteTarget.Cellular,
                selectedNetwork = cellular,
            ),
            decision,
        )
        assertEquals(RotationEvent.NetworkReturned, decision.event)
    }

    @Test
    fun `automatic route return selects the first available network in monitor order`() {
        val vpn =
            network(
                id = "vpn-1",
                category = NetworkCategory.Vpn,
                isAvailable = true,
            )

        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Automatic,
                networks =
                    listOf(
                        network(id = "wifi-1", category = NetworkCategory.WiFi, isAvailable = false),
                        vpn,
                        network(id = "cell-1", category = NetworkCategory.Cellular, isAvailable = true),
                    ),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 5_000,
                networkReturnTimeout = 60.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.Returned(
                routeTarget = RouteTarget.Automatic,
                selectedNetwork = vpn,
            ),
            decision,
        )
        assertEquals(RotationEvent.NetworkReturned, decision.event)
    }

    @Test
    fun `rotation network return waits before timeout when selected route is still unavailable`() {
        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks =
                    listOf(
                        network(id = "wifi-1", category = NetworkCategory.WiFi, isAvailable = true),
                        network(id = "cell-1", category = NetworkCategory.Cellular, isAvailable = false),
                    ),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 21_250,
                networkReturnTimeout = 60.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.Waiting(
                routeTarget = RouteTarget.Cellular,
                remainingReturnTime = 39_750.milliseconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `rotation network return times out when selected route remains unavailable`() {
        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks =
                    listOf(
                        network(id = "wifi-1", category = NetworkCategory.WiFi, isAvailable = true),
                    ),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 61_000,
                networkReturnTimeout = 60.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.TimedOut(routeTarget = RouteTarget.Cellular),
            decision,
        )
        assertEquals(RotationEvent.NetworkReturnTimedOut, decision.event)
    }

    @Test
    fun `zero network return timeout fails immediately when selected route is unavailable`() {
        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Vpn,
                networks = emptyList(),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                networkReturnTimeout = 0.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.TimedOut(routeTarget = RouteTarget.Vpn),
            decision,
        )
        assertEquals(RotationEvent.NetworkReturnTimedOut, decision.event)
    }

    @Test
    fun `rotation network return fails closed when wait start time is observed in the future`() {
        val decision =
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = 5_000,
                nowElapsedMillis = 4_000,
                networkReturnTimeout = 3.seconds,
            )

        assertEquals(
            RotationNetworkReturnDecision.Waiting(
                routeTarget = RouteTarget.Cellular,
                remainingReturnTime = 4.seconds,
            ),
            decision,
        )
        assertEquals(null, decision.event)
    }

    @Test
    fun `invalid rotation network return inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = -1,
                nowElapsedMillis = 1_000,
                networkReturnTimeout = 60.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = -1,
                networkReturnTimeout = 60.seconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnPolicy.evaluate(
                routeTarget = RouteTarget.Cellular,
                networks = emptyList(),
                waitStartedElapsedMillis = 1_000,
                nowElapsedMillis = 1_000,
                networkReturnTimeout = (-1).seconds,
            )
        }
    }

    @Test
    fun `public network return decisions reject contradictory data`() {
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnDecision.Returned(
                routeTarget = RouteTarget.Cellular,
                selectedNetwork =
                    network(
                        id = "wifi-1",
                        category = NetworkCategory.WiFi,
                        isAvailable = true,
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnDecision.Returned(
                routeTarget = RouteTarget.Cellular,
                selectedNetwork =
                    network(
                        id = "cell-1",
                        category = NetworkCategory.Cellular,
                        isAvailable = false,
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RotationNetworkReturnDecision.Waiting(
                routeTarget = RouteTarget.Cellular,
                remainingReturnTime = 0.seconds,
            )
        }
    }

    private fun network(
        id: String,
        category: NetworkCategory,
        isAvailable: Boolean,
    ): NetworkDescriptor =
        NetworkDescriptor(
            id = id,
            category = category,
            displayName = id,
            isAvailable = isAvailable,
        )
}
