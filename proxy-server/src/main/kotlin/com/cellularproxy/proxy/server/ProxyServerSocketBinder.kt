package com.cellularproxy.proxy.server

import com.cellularproxy.shared.proxy.ProxyStartupError
import java.io.Closeable
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.net.UnknownHostException

sealed interface ProxyServerSocketBindResult {
    data class Bound(val listener: BoundProxyServerSocket) : ProxyServerSocketBindResult
    data class Failed(val startupError: ProxyStartupError) : ProxyServerSocketBindResult
}

class BoundProxyServerSocket internal constructor(
    private val serverSocket: ServerSocket,
    val listenHost: String,
) : Closeable {
    val listenPort: Int
        get() = serverSocket.localPort

    fun accept(): ProxyClientStreamConnection {
        val socket = serverSocket.accept()
        return ProxyClientStreamConnection(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            shutdownInputAction = { socket.shutdownInput() },
            shutdownOutputAction = { socket.shutdownOutput() },
        )
    }

    override fun close() {
        serverSocket.close()
    }
}

object ProxyServerSocketBinder {
    fun bind(
        listenHost: String,
        listenPort: Int,
        backlog: Int = DEFAULT_SERVER_SOCKET_BACKLOG,
    ): ProxyServerSocketBindResult {
        if (!listenHost.isSupportedNumericIpv4Address()) {
            return ProxyServerSocketBindResult.Failed(ProxyStartupError.InvalidListenAddress)
        }
        if (listenPort !in TCP_PORT_RANGE) {
            return ProxyServerSocketBindResult.Failed(ProxyStartupError.InvalidListenPort)
        }
        require(backlog > 0) { "Server socket backlog must be positive" }

        return bindSocket(listenHost = listenHost, listenPort = listenPort, backlog = backlog)
    }

    internal fun bindEphemeral(
        listenHost: String,
        backlog: Int = DEFAULT_SERVER_SOCKET_BACKLOG,
    ): ProxyServerSocketBindResult {
        if (!listenHost.isSupportedNumericIpv4Address()) {
            return ProxyServerSocketBindResult.Failed(ProxyStartupError.InvalidListenAddress)
        }
        require(backlog > 0) { "Server socket backlog must be positive" }

        return bindSocket(listenHost = listenHost, listenPort = 0, backlog = backlog)
    }

    private fun bindSocket(
        listenHost: String,
        listenPort: Int,
        backlog: Int,
    ): ProxyServerSocketBindResult {
        val socket = ServerSocket()
        return try {
            socket.bind(InetSocketAddress(listenHost.toInetAddress(), listenPort), backlog)
            ProxyServerSocketBindResult.Bound(
                BoundProxyServerSocket(
                    serverSocket = socket,
                    listenHost = listenHost,
                ),
            )
        } catch (_: BindException) {
            socket.closeQuietly()
            ProxyServerSocketBindResult.Failed(listenHost.bindExceptionStartupError())
        } catch (_: SocketException) {
            socket.closeQuietly()
            ProxyServerSocketBindResult.Failed(ProxyStartupError.InvalidListenAddress)
        } catch (_: UnknownHostException) {
            socket.closeQuietly()
            ProxyServerSocketBindResult.Failed(ProxyStartupError.InvalidListenAddress)
        }
    }
}

private val TCP_PORT_RANGE = 1..65_535
private const val DEFAULT_SERVER_SOCKET_BACKLOG = 50
private const val BROAD_LISTEN_HOST = "0.0.0.0"

private fun String.isSupportedNumericIpv4Address(): Boolean {
    if (isEmpty() || this != trim()) {
        return false
    }

    val parts = split(".")
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
}

private fun String.toInetAddress(): InetAddress {
    val bytes = split(".").map { it.toInt().toByte() }.toByteArray()
    return InetAddress.getByAddress(this, bytes)
}

private fun String.bindExceptionStartupError(): ProxyStartupError =
    if (this == BROAD_LISTEN_HOST || toInetAddress().isAssignedToLocalInterface()) {
        ProxyStartupError.PortAlreadyInUse
    } else {
        ProxyStartupError.InvalidListenAddress
    }

private fun InetAddress.isAssignedToLocalInterface(): Boolean {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    while (interfaces.hasMoreElements()) {
        val addresses = interfaces.nextElement().inetAddresses
        while (addresses.hasMoreElements()) {
            if (addresses.nextElement() == this) {
                return true
            }
        }
    }
    return false
}

private fun ServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Binding already failed; startup error classification should remain stable.
    }
}
