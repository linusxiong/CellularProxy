package com.cellularproxy.shared.network

import com.cellularproxy.shared.config.RouteTarget

data class NetworkDescriptor(
    val id: String,
    val category: NetworkCategory,
    val displayName: String,
    val isAvailable: Boolean,
)

enum class NetworkCategory {
    WiFi,
    Cellular,
    Vpn,
}

enum class NetworkTransport {
    WiFi,
    Cellular,
    Vpn,
    Other,
}

data class NetworkSnapshot(
    val id: String,
    val displayName: String,
    val isAvailable: Boolean,
    val transports: Set<NetworkTransport>,
)

fun NetworkSnapshot.toNetworkDescriptorOrNull(): NetworkDescriptor? {
    val category = when {
        NetworkTransport.Vpn in transports -> NetworkCategory.Vpn
        transports == setOf(NetworkTransport.WiFi) -> NetworkCategory.WiFi
        transports == setOf(NetworkTransport.Cellular) -> NetworkCategory.Cellular
        else -> null
    }

    return category?.let {
        NetworkDescriptor(
            id = id,
            category = it,
            displayName = displayName,
            isAvailable = isAvailable,
        )
    }
}

object RouteSelector {
    fun candidatesFor(
        target: RouteTarget,
        networks: List<NetworkDescriptor>,
    ): List<NetworkDescriptor> {
        val availableNetworks = networks.filter(NetworkDescriptor::isAvailable)

        return when (target) {
            RouteTarget.WiFi -> availableNetworks.filter { it.category == NetworkCategory.WiFi }
            RouteTarget.Cellular -> availableNetworks.filter { it.category == NetworkCategory.Cellular }
            RouteTarget.Vpn -> availableNetworks.filter { it.category == NetworkCategory.Vpn }
            RouteTarget.Automatic -> availableNetworks
        }
    }
}
