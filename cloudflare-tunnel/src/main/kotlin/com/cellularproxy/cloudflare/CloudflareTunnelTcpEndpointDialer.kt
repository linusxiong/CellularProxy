package com.cellularproxy.cloudflare

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException

class CloudflareTunnelTcpEndpointDialer(
    private val socketFactory: CloudflareTunnelTcpSocketFactory = CloudflareTunnelTcpSocketFactory { Socket() },
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) : CloudflareTunnelEndpointDialer {
    init {
        require(connectTimeoutMillis > 0) {
            "Cloudflare tunnel TCP connect timeout must be positive"
        }
    }

    override fun connect(
        endpoint: CloudflareTunnelEdgeEndpoint,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelEdgeConnectionResult {
        val socket = socketFactory.create()
        return try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            CloudflareTunnelEdgeConnectionResult.Connected(CloudflareTunnelTcpEdgeConnection(socket))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            socket.closeBestEffort()
            throw exception
        } catch (exception: CancellationException) {
            socket.closeBestEffort()
            throw exception
        } catch (_: Exception) {
            socket.closeBestEffort()
            CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable)
        }
    }
}

fun interface CloudflareTunnelTcpSocketFactory {
    fun create(): Socket
}

class CloudflareTunnelTcpEdgeConnection internal constructor(
    internal val socket: Socket,
) : CloudflareTunnelEdgeConnection {
    override fun close() {
        socket.close()
    }

    override fun toString(): String = "CloudflareTunnelTcpEdgeConnection(socket=<redacted>)"
}

private fun Socket.closeBestEffort() {
    try {
        close()
    } catch (_: Exception) {
        // Dial failure is already represented by EdgeUnavailable; cleanup failure adds no safe detail.
    }
}

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
