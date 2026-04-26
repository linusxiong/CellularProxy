package com.cellularproxy.app.service

import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.network.BoundSocketProvider
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnection
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnection
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.shared.config.RouteTarget
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

data class ProxyRuntimeOutboundConnectors(
    val httpConnector: OutboundHttpOriginConnector,
    val connectConnector: OutboundConnectTunnelConnector,
)

object ProxyRuntimeOutboundConnectorFactory {
    fun create(
        route: RouteTarget,
        socketProvider: BoundSocketProvider,
        connectTimeoutMillis: Long = DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS,
    ): ProxyRuntimeOutboundConnectors {
        require(connectTimeoutMillis > 0) { "Outbound connect timeout must be positive" }

        return ProxyRuntimeOutboundConnectors(
            httpConnector = OutboundHttpOriginConnector { request ->
                when (
                    val result = socketProvider.connectBlocking(
                        route = route,
                        host = request.host,
                        port = request.port,
                        timeoutMillis = connectTimeoutMillis,
                    )
                ) {
                    is BoundSocketConnectResult.Connected ->
                        OutboundHttpOriginOpenResult.Connected(
                            OutboundHttpOriginConnection(
                                input = result.socket.getInputStream(),
                                output = result.socket.getOutputStream(),
                                host = request.host,
                                port = request.port,
                            ),
                        )
                    is BoundSocketConnectResult.Failed ->
                        OutboundHttpOriginOpenResult.Failed(result.reason.toHttpOriginFailure())
                }
            },
            connectConnector = OutboundConnectTunnelConnector { request ->
                when (
                    val result = socketProvider.connectBlocking(
                        route = route,
                        host = request.host,
                        port = request.port,
                        timeoutMillis = connectTimeoutMillis,
                    )
                ) {
                    is BoundSocketConnectResult.Connected ->
                        OutboundConnectTunnelOpenResult.Connected(
                            OutboundConnectTunnelConnection(
                                input = result.socket.getInputStream(),
                                output = result.socket.getOutputStream(),
                                host = request.host,
                                port = request.port,
                                shutdownInputAction = { result.socket.shutdownInputQuietly() },
                                shutdownOutputAction = { result.socket.shutdownOutputQuietly() },
                            ),
                        )
                    is BoundSocketConnectResult.Failed ->
                        OutboundConnectTunnelOpenResult.Failed(result.reason.toConnectTunnelFailure())
                }
            },
        )
    }
}

private fun BoundSocketProvider.connectBlocking(
    route: RouteTarget,
    host: String,
    port: Int,
    timeoutMillis: Long,
): BoundSocketConnectResult {
    val latch = CountDownLatch(1)
    val resultRef = AtomicReference<Result<BoundSocketConnectResult>>()
    val abandoned = AtomicBoolean(false)

    suspend {
        connect(
            route = route,
            host = host,
            port = port,
            timeoutMillis = timeoutMillis,
        )
    }.startCoroutine(
        object : Continuation<BoundSocketConnectResult> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<BoundSocketConnectResult>) {
                if (abandoned.get()) {
                    result.closeConnectedSocketQuietly()
                } else {
                    resultRef.set(result)
                }
                latch.countDown()
            }
        },
    )

    try {
        latch.await()
    } catch (interrupted: InterruptedException) {
        abandoned.set(true)
        resultRef.get()?.closeConnectedSocketQuietly()
        Thread.currentThread().interrupt()
        throw interrupted
    }
    return resultRef.get().getOrThrow()
}

private fun Result<BoundSocketConnectResult>.closeConnectedSocketQuietly() {
    getOrNull()?.let { result ->
        if (result is BoundSocketConnectResult.Connected) {
            result.socket.closeQuietly()
        }
    }
}

private fun BoundSocketConnectFailure.toHttpOriginFailure(): OutboundHttpOriginOpenFailure =
    when (this) {
        BoundSocketConnectFailure.SelectedRouteUnavailable ->
            OutboundHttpOriginOpenFailure.SelectedRouteUnavailable
        BoundSocketConnectFailure.DnsResolutionFailed ->
            OutboundHttpOriginOpenFailure.DnsResolutionFailed
        BoundSocketConnectFailure.ConnectionFailed ->
            OutboundHttpOriginOpenFailure.OutboundConnectionFailed
        BoundSocketConnectFailure.ConnectionTimedOut ->
            OutboundHttpOriginOpenFailure.OutboundConnectionTimeout
    }

private fun BoundSocketConnectFailure.toConnectTunnelFailure(): OutboundConnectTunnelOpenFailure =
    when (this) {
        BoundSocketConnectFailure.SelectedRouteUnavailable ->
            OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable
        BoundSocketConnectFailure.DnsResolutionFailed ->
            OutboundConnectTunnelOpenFailure.DnsResolutionFailed
        BoundSocketConnectFailure.ConnectionFailed ->
            OutboundConnectTunnelOpenFailure.OutboundConnectionFailed
        BoundSocketConnectFailure.ConnectionTimedOut ->
            OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout
    }

private fun Socket.shutdownInputQuietly() {
    try {
        shutdownInput()
    } catch (_: Exception) {
        // Shutdown races during tunnel relay cleanup should not replace the relay result.
    }
}

private fun Socket.shutdownOutputQuietly() {
    try {
        shutdownOutput()
    } catch (_: Exception) {
        // Shutdown races during tunnel relay cleanup should not replace the relay result.
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Late connect results after interruption no longer have an owner.
    }
}

private const val DEFAULT_OUTBOUND_CONNECT_TIMEOUT_MILLIS = 30_000L
