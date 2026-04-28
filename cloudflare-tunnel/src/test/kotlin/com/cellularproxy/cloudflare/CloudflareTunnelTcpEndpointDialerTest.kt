package com.cellularproxy.cloudflare

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelTcpEndpointDialerTest {
    @Test
    fun `successful dial opens endpoint socket with configured timeout`() {
        val socket = RecordingSocket()
        val dialer =
            CloudflareTunnelTcpEndpointDialer(
                socketFactory = CloudflareTunnelTcpSocketFactory { socket },
                connectTimeoutMillis = 1_250,
            )

        val result =
            assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(
                dialer.connect(CloudflareTunnelEdgeEndpoint("edge.example.com", 7844), credentials()),
            )

        assertSame(socket, (result.connection as CloudflareTunnelTcpEdgeConnection).socket)
        assertEquals(InetSocketAddress("edge.example.com", 7844), socket.connectedAddress)
        assertEquals(1_250, socket.connectedTimeoutMillis)
    }

    @Test
    fun `connection close closes the underlying socket`() {
        val socket = RecordingSocket()
        val dialer =
            CloudflareTunnelTcpEndpointDialer(
                socketFactory = CloudflareTunnelTcpSocketFactory { socket },
            )

        val result =
            assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(
                dialer.connect(CloudflareTunnelEdgeEndpoint("edge.example.com", 7844), credentials()),
            )

        result.connection.close()

        assertTrue(socket.closed)
    }

    @Test
    fun `failed dial closes socket and returns edge unavailable`() {
        val socket = RecordingSocket(connectException = java.io.IOException("raw token tunnel-secret"))
        val dialer =
            CloudflareTunnelTcpEndpointDialer(
                socketFactory = CloudflareTunnelTcpSocketFactory { socket },
            )

        val result =
            assertIs<CloudflareTunnelEdgeConnectionResult.Failed>(
                dialer.connect(CloudflareTunnelEdgeEndpoint("edge.example.com", 7844), credentials()),
            )

        assertEquals(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable, result.failure)
        assertTrue(socket.closed)
    }

    @Test
    fun `connect cancellation closes socket and is rethrown`() {
        val socket = RecordingSocket(connectRuntimeException = CancellationException("cancel connect"))
        val dialer =
            CloudflareTunnelTcpEndpointDialer(
                socketFactory = CloudflareTunnelTcpSocketFactory { socket },
            )

        assertFailsWith<CancellationException> {
            dialer.connect(CloudflareTunnelEdgeEndpoint("edge.example.com", 7844), credentials())
        }
        assertTrue(socket.closed)
    }

    @Test
    fun `invalid timeout is rejected before creating socket`() {
        var created = false

        assertFailsWith<IllegalArgumentException> {
            CloudflareTunnelTcpEndpointDialer(
                socketFactory =
                    CloudflareTunnelTcpSocketFactory {
                        created = true
                        RecordingSocket()
                    },
                connectTimeoutMillis = 0,
            )
        }
        assertEquals(false, created)
    }

    private fun credentials(): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        tunnelSecret = ByteArray(32) { index -> index.toByte() },
        endpoint = null,
    )

    private class RecordingSocket(
        private val connectException: java.io.IOException? = null,
        private val connectRuntimeException: RuntimeException? = null,
    ) : Socket() {
        var connectedAddress: SocketAddress? = null
            private set
        var connectedTimeoutMillis: Int? = null
            private set
        var closed = false
            private set

        override fun connect(
            endpoint: SocketAddress?,
            timeout: Int,
        ) {
            connectRuntimeException?.let { throw it }
            connectException?.let { throw it }
            connectedAddress = endpoint
            connectedTimeoutMillis = timeout
        }

        override fun close() {
            closed = true
        }
    }
}
