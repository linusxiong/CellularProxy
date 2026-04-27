package com.cellularproxy.shared.network

import com.cellularproxy.shared.config.RouteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteSelectorTest {
    @Test
    fun `explicit route targets select only available networks in the requested category`() {
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)
        val cellular = NetworkDescriptor("cell", NetworkCategory.Cellular, "Carrier", isAvailable = true)
        val vpn = NetworkDescriptor("vpn", NetworkCategory.Vpn, "Tailscale", isAvailable = true)
        val unavailableWifi = NetworkDescriptor("offline-wifi", NetworkCategory.WiFi, "Offline Wi-Fi", isAvailable = false)
        val networks = listOf(wifi, cellular, vpn, unavailableWifi)

        assertEquals(listOf(wifi), RouteSelector.candidatesFor(RouteTarget.WiFi, networks))
        assertEquals(listOf(cellular), RouteSelector.candidatesFor(RouteTarget.Cellular, networks))
        assertEquals(listOf(vpn), RouteSelector.candidatesFor(RouteTarget.Vpn, networks))
    }

    @Test
    fun `automatic route target preserves monitor order while excluding unavailable networks`() {
        val cellular = NetworkDescriptor("cell", NetworkCategory.Cellular, "Carrier", isAvailable = true)
        val unavailableVpn = NetworkDescriptor("vpn", NetworkCategory.Vpn, "Tailscale", isAvailable = false)
        val wifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true)

        val candidates = RouteSelector.candidatesFor(RouteTarget.Automatic, listOf(cellular, unavailableVpn, wifi))

        assertEquals(listOf(cellular, wifi), candidates)
    }

    @Test
    fun `explicit route target returns no candidates when selected category is unavailable`() {
        val unavailableWifi = NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = false)
        val cellular = NetworkDescriptor("cell", NetworkCategory.Cellular, "Carrier", isAvailable = true)

        val candidates = RouteSelector.candidatesFor(RouteTarget.WiFi, listOf(unavailableWifi, cellular))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `network snapshot with exactly WiFi transport converts to WiFi descriptor`() {
        val snapshot =
            NetworkSnapshot(
                id = "wifi",
                displayName = "Home Wi-Fi",
                isAvailable = true,
                transports = setOf(NetworkTransport.WiFi),
            )

        assertEquals(
            NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = true),
            snapshot.toNetworkDescriptorOrNull(),
        )
    }

    @Test
    fun `network snapshot with exactly Cellular transport converts to Cellular descriptor`() {
        val snapshot =
            NetworkSnapshot(
                id = "cell",
                displayName = "Carrier",
                isAvailable = true,
                transports = setOf(NetworkTransport.Cellular),
            )

        assertEquals(
            NetworkDescriptor("cell", NetworkCategory.Cellular, "Carrier", isAvailable = true),
            snapshot.toNetworkDescriptorOrNull(),
        )
    }

    @Test
    fun `network snapshot with VPN transport converts to VPN descriptor before underlay transports`() {
        val snapshot =
            NetworkSnapshot(
                id = "vpn",
                displayName = "Work VPN",
                isAvailable = true,
                transports = setOf(NetworkTransport.Vpn, NetworkTransport.WiFi, NetworkTransport.Other),
            )

        assertEquals(
            NetworkDescriptor("vpn", NetworkCategory.Vpn, "Work VPN", isAvailable = true),
            snapshot.toNetworkDescriptorOrNull(),
        )
    }

    @Test
    fun `network snapshot conversion preserves unavailable state`() {
        val snapshot =
            NetworkSnapshot(
                id = "wifi",
                displayName = "Home Wi-Fi",
                isAvailable = false,
                transports = setOf(NetworkTransport.WiFi),
            )

        assertEquals(
            NetworkDescriptor("wifi", NetworkCategory.WiFi, "Home Wi-Fi", isAvailable = false),
            snapshot.toNetworkDescriptorOrNull(),
        )
    }

    @Test
    fun `network snapshot with unrouteable transports returns null`() {
        assertNull(
            NetworkSnapshot(
                id = "empty",
                displayName = "No transports",
                isAvailable = true,
                transports = emptySet(),
            ).toNetworkDescriptorOrNull(),
        )
        assertNull(
            NetworkSnapshot(
                id = "other",
                displayName = "Other only",
                isAvailable = true,
                transports = setOf(NetworkTransport.Other),
            ).toNetworkDescriptorOrNull(),
        )
        assertNull(
            NetworkSnapshot(
                id = "wifi-cell",
                displayName = "Ambiguous physical",
                isAvailable = true,
                transports = setOf(NetworkTransport.WiFi, NetworkTransport.Cellular),
            ).toNetworkDescriptorOrNull(),
        )
        assertNull(
            NetworkSnapshot(
                id = "wifi-other",
                displayName = "Ambiguous known physical",
                isAvailable = true,
                transports = setOf(NetworkTransport.WiFi, NetworkTransport.Other),
            ).toNetworkDescriptorOrNull(),
        )
        assertNull(
            NetworkSnapshot(
                id = "cell-other",
                displayName = "Ambiguous known physical",
                isAvailable = true,
                transports = setOf(NetworkTransport.Cellular, NetworkTransport.Other),
            ).toNetworkDescriptorOrNull(),
        )
        assertNull(
            NetworkSnapshot(
                id = "wifi-cell-other",
                displayName = "Ambiguous physical and unsupported",
                isAvailable = true,
                transports = setOf(NetworkTransport.WiFi, NetworkTransport.Cellular, NetworkTransport.Other),
            ).toNetworkDescriptorOrNull(),
        )
    }
}
