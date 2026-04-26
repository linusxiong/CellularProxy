package com.cellularproxy.proxy.server

import com.cellularproxy.shared.proxy.ProxyStartupError
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyServerSocketBinderTest {
    @Test
    fun `binds supported listen address and accepts client stream connection`() {
        val bound = assertIs<ProxyServerSocketBindResult.Bound>(
            ProxyServerSocketBinder.bindEphemeral(listenHost = LOOPBACK_HOST),
        )

        bound.listener.use { listener ->
            assertEquals(LOOPBACK_HOST, listener.listenHost)
            assertTrue(listener.listenPort in 1..65_535)

            val clientFailure = AtomicReference<Throwable?>()
            val client = thread(start = true) {
                try {
                    Socket(LOOPBACK_HOST, listener.listenPort).use { socket ->
                        socket.getOutputStream().write("ping\n".toByteArray(Charsets.US_ASCII))
                        socket.shutdownOutput()
                        val response = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                            .readLine()
                        assertEquals("pong", response)
                    }
                } catch (throwable: Throwable) {
                    clientFailure.set(throwable)
                }
            }

            listener.accept().use { connection ->
                val request = BufferedReader(InputStreamReader(connection.input, Charsets.US_ASCII)).readLine()
                assertEquals("ping", request)
                connection.output.write("pong\n".toByteArray(Charsets.US_ASCII))
                connection.output.flush()
            }
            client.join(1_000)
            assertTrue(!client.isAlive, "client thread should finish after accepted connection is handled")
            clientFailure.get()?.let { throw it }
        }
    }

    @Test
    fun `invalid listen host fails before binding`() {
        val result = ProxyServerSocketBinder.bind(listenHost = "localhost", listenPort = 8080)

        val failed = assertIs<ProxyServerSocketBindResult.Failed>(result)
        assertEquals(ProxyStartupError.InvalidListenAddress, failed.startupError)
    }

    @Test
    fun `invalid listen port fails before binding`() {
        val low = assertIs<ProxyServerSocketBindResult.Failed>(
            ProxyServerSocketBinder.bind(listenHost = LOOPBACK_HOST, listenPort = 0),
        )
        val high = assertIs<ProxyServerSocketBindResult.Failed>(
            ProxyServerSocketBinder.bind(listenHost = LOOPBACK_HOST, listenPort = 65_536),
        )

        assertEquals(ProxyStartupError.InvalidListenPort, low.startupError)
        assertEquals(ProxyStartupError.InvalidListenPort, high.startupError)
    }

    @Test
    fun `occupied listen port maps to startup port already in use`() {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).use { occupied ->
            val result = ProxyServerSocketBinder.bind(
                listenHost = LOOPBACK_HOST,
                listenPort = occupied.localPort,
            )

            val failed = assertIs<ProxyServerSocketBindResult.Failed>(result)
            assertEquals(ProxyStartupError.PortAlreadyInUse, failed.startupError)
        }
    }

    @Test
    fun `unassigned listen address maps to invalid listen address instead of port conflict`() {
        val result = ProxyServerSocketBinder.bind(listenHost = UNASSIGNED_TEST_NET_HOST, listenPort = 8080)

        val failed = assertIs<ProxyServerSocketBindResult.Failed>(result)
        assertEquals(ProxyStartupError.InvalidListenAddress, failed.startupError)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val UNASSIGNED_TEST_NET_HOST = "192.0.2.1"
    }
}
