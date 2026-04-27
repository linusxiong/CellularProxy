package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.network.RouteSelector
import java.net.Socket

interface BoundSocketProvider {
    suspend fun connect(
        route: RouteTarget,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult
}

fun interface BoundNetworkSocketConnector {
    suspend fun connect(
        network: NetworkDescriptor,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult
}

class RouteBoundSocketProvider(
    private val observedNetworks: () -> List<NetworkDescriptor>,
    private val connector: BoundNetworkSocketConnector,
) : BoundSocketProvider {
    override suspend fun connect(
        route: RouteTarget,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in VALID_PORT_RANGE) { "Port must be in range 1..65535" }
        require(timeoutMillis > 0) { "Timeout must be positive" }

        val selectedNetwork =
            RouteSelector
                .candidatesFor(route, observedNetworks())
                .firstOrNull()
                ?: return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)

        val result =
            connector.connect(
                network = selectedNetwork,
                host = host,
                port = port,
                timeoutMillis = timeoutMillis,
            )

        if (result is BoundSocketConnectResult.Connected && result.network != selectedNetwork) {
            result.socket.closeQuietly()
            require(result.network == selectedNetwork) {
                "Connector returned a socket for a different network"
            }
        }

        return result
    }
}

sealed interface BoundSocketConnectResult {
    data class Connected(
        val socket: Socket,
        val network: NetworkDescriptor,
    ) : BoundSocketConnectResult {
        init {
            require(!socket.isClosed) { "Connected socket must not be closed" }
            require(socket.isConnected) { "Connected socket must already be connected" }
            require(network.isAvailable) { "Connected network must be available" }
        }
    }

    data class Failed(
        val reason: BoundSocketConnectFailure,
    ) : BoundSocketConnectResult
}

enum class BoundSocketConnectFailure {
    SelectedRouteUnavailable,
    DnsResolutionFailed,
    ConnectionFailed,
    ConnectionTimedOut,
}

private val VALID_PORT_RANGE = 1..65_535

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Discard cleanup failures while enforcing connector invariants.
    }
}
