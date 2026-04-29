package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConnectTunnelOutboundExchangeHandlerTest {
    @Test
    fun `separately callable path establishes tunnel then relays both directions and closes owned streams`() {
        val accepted = accepted(connectRequest(host = "origin.example", port = 443))
        val clientInput = CloseTrackingInputStream("client request".toByteArray(Charsets.UTF_8))
        val clientOutput = CloseTrackingOutputStream()
        var clientInputShutdowns = 0
        var clientOutputShutdowns = 0
        val originInput = CloseTrackingInputStream("origin response".toByteArray(Charsets.UTF_8))
        val originOutput = CloseTrackingOutputStream()
        val originConnection =
            OutboundConnectTunnelConnection(
                input = originInput,
                output = originOutput,
                host = "origin.example",
                port = 443,
            )
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Connected(originConnection),
            )

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted = accepted,
                client =
                    ConnectTunnelClientConnection(
                        input = clientInput,
                        output = clientOutput,
                        shutdownInputAction = { clientInputShutdowns += 1 },
                        shutdownOutputAction = { clientOutputShutdowns += 1 },
                    ),
                relayBufferSize = 4,
            )

        assertEquals(listOf("origin.example" to 443), connector.openedOrigins)
        val relayed = assertIs<ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed>(result)
        assertEquals("origin.example", relayed.host)
        assertEquals(443, relayed.port)
        assertEquals(
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8).size,
            relayed.responseBytesWritten,
        )
        val relayResult = assertIs<ConnectTunnelBidirectionalRelayResult.Completed>(relayed.relayResult)
        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 14,
            ),
            relayResult.clientToOrigin,
        )
        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = 15,
            ),
            relayResult.originToClient,
        )
        assertEquals("client request", originOutput.toString(Charsets.UTF_8))
        assertEquals(
            "HTTP/1.1 200 Connection Established\r\n\r\norigin response",
            clientOutput.toString(Charsets.UTF_8),
        )
        assertEquals(true, clientInput.wasClosed)
        assertEquals(true, clientOutput.wasClosed)
        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
        assertEquals(1, clientInputShutdowns)
        assertEquals(1, clientOutputShutdowns)
    }

    @Test
    fun `separately callable relay path returns unsupported for non-connect accepted requests without touching streams or origin`() {
        val connector = FailingConnectTunnelConnector()
        val clientInput = ThrowingReadInputStream()
        val clientOutput = ByteArrayOutputStream()

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted =
                    accepted(
                        ParsedHttpRequest(
                            request =
                                ParsedProxyRequest.HttpProxy(
                                    method = "GET",
                                    host = "origin.example",
                                    port = 80,
                                    originTarget = "/",
                                ),
                            headers = emptyMap(),
                        ),
                    ),
                client =
                    ConnectTunnelClientConnection(
                        input = clientInput,
                        output = clientOutput,
                    ),
            )

        assertEquals(
            ConnectTunnelOutboundExchangeRelayHandlingResult.UnsupportedAcceptedRequest(
                ConnectTunnelOutboundExchangeUnsupportedReason.NotConnectTunnelRequest,
            ),
            result,
        )
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
    }

    @Test
    fun `separately callable relay path maps outbound open failures to sanitized proxy error responses`() {
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout),
            )
        val clientInput = ThrowingReadInputStream()
        val clientOutput = ByteArrayOutputStream()

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted = accepted(connectRequest(host = "secret.example", port = 443)),
                client =
                    ConnectTunnelClientConnection(
                        input = clientInput,
                        output = clientOutput,
                    ),
            )

        val failed = assertIs<ConnectTunnelOutboundExchangeRelayHandlingResult.ConnectionFailed>(result)
        assertEquals(ProxyServerFailure.OutboundConnectionTimeout, failed.failure)
        assertEquals(clientOutput.size(), failed.errorResponseBytesWritten)
        assertContains(clientOutput.toString(Charsets.UTF_8), "HTTP/1.1 504 Gateway Timeout")
        assertEquals(false, clientOutput.toString(Charsets.UTF_8).contains("secret.example"))
    }

    @Test
    fun `separately callable relay path propagates established response write failure and closes opened origin`() {
        val originInput = CloseTrackingInputStream(ByteArray(0))
        val originOutput = CloseTrackingOutputStream()
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Connected(
                    OutboundConnectTunnelConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 443,
                    ),
                ),
            )

        assertFailsWith<IOException> {
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted = accepted(connectRequest()),
                client =
                    ConnectTunnelClientConnection(
                        input = CloseTrackingInputStream(ByteArray(0)),
                        output = ThrowingWriteOutputStream(),
                    ),
            )
        }

        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `separately callable relay path rejects invalid relay buffer before opening origin or writing client output`() {
        val connector = FailingConnectTunnelConnector()
        val clientOutput = ByteArrayOutputStream()

        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted = accepted(connectRequest()),
                client =
                    ConnectTunnelClientConnection(
                        input = CloseTrackingInputStream(ByteArray(0)),
                        output = clientOutput,
                    ),
                relayBufferSize = 0,
            )
        }

        assertEquals(emptyList(), clientOutput.toByteArray().toList())
    }

    @Test
    fun `separately callable relay path returns failed relay result and closes resources after post-handshake failure`() {
        val accepted = accepted(connectRequest(host = "origin.example", port = 443))
        val clientInput = CloseTrackingInputStream(ByteArray(0))
        val clientOutput = CloseTrackingOutputStream()
        val originInput = ThrowingCloseTrackingInputStream()
        val originOutput = CloseTrackingOutputStream()
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Connected(
                    OutboundConnectTunnelConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 443,
                    ),
                ),
            )

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handleAndRelay(
                accepted = accepted,
                client =
                    ConnectTunnelClientConnection(
                        input = clientInput,
                        output = clientOutput,
                    ),
                relayBufferSize = 8,
            )

        val relayed = assertIs<ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed>(result)
        val relayResult = assertIs<ConnectTunnelBidirectionalRelayResult.Failed>(relayed.relayResult)
        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = 0,
                reason = ConnectTunnelStreamRelayFailure.SourceReadFailed,
            ),
            relayResult.originToClient,
        )
        assertContains(clientOutput.toString(Charsets.UTF_8), "HTTP/1.1 200 Connection Established")
        assertEquals(true, clientInput.wasClosed)
        assertEquals(true, clientOutput.wasClosed)
        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `opens tunnel origin and writes connection established response`() {
        val accepted = accepted(connectRequest(host = "origin.example", port = 443))
        val clientOutput = ByteArrayOutputStream()
        val originInput = CloseTrackingInputStream(ByteArray(0))
        val originOutput = CloseTrackingOutputStream()
        val originConnection =
            OutboundConnectTunnelConnection(
                input = originInput,
                output = originOutput,
                host = "origin.example",
                port = 443,
            )
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Connected(originConnection),
            )

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientOutput = clientOutput,
            )

        assertEquals(listOf("origin.example" to 443), connector.openedOrigins)
        val established = assertIs<ConnectTunnelOutboundExchangeHandlingResult.Established>(result)
        assertEquals("origin.example", established.host)
        assertEquals(443, established.port)
        assertEquals(originConnection, established.connection)
        assertEquals(
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8).size,
            established.responseBytesWritten,
        )
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n", clientOutput.toString(Charsets.UTF_8))
        assertEquals(false, originInput.wasClosed)
        assertEquals(false, originOutput.wasClosed)
    }

    @Test
    fun `returns unsupported for non-connect accepted requests without opening origin or writing client output`() {
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.DnsResolutionFailed),
            )
        val clientOutput = ByteArrayOutputStream()

        val httpResult =
            ConnectTunnelOutboundExchangeHandler(connector).handle(
                accepted =
                    accepted(
                        ParsedHttpRequest(
                            request =
                                ParsedProxyRequest.HttpProxy(
                                    method = "GET",
                                    host = "origin.example",
                                    port = 80,
                                    originTarget = "/",
                                ),
                            headers = emptyMap(),
                        ),
                    ),
                clientOutput = clientOutput,
            )

        val managementResult =
            ConnectTunnelOutboundExchangeHandler(connector).handle(
                accepted =
                    accepted(
                        ParsedHttpRequest(
                            request =
                                ParsedProxyRequest.Management(
                                    method = HttpMethod.Get,
                                    originTarget = "/health",
                                    requiresToken = false,
                                    requiresAuditLog = false,
                                ),
                            headers = emptyMap(),
                        ),
                    ),
                clientOutput = clientOutput,
            )

        assertEquals(
            ConnectTunnelOutboundExchangeHandlingResult.UnsupportedAcceptedRequest(
                ConnectTunnelOutboundExchangeUnsupportedReason.NotConnectTunnelRequest,
            ),
            httpResult,
        )
        assertEquals(httpResult, managementResult)
        assertEquals(emptyList(), connector.openedOrigins)
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
    }

    @Test
    fun `maps outbound open failures to sanitized proxy error responses`() {
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout),
            )
        val clientOutput = ByteArrayOutputStream()

        val result =
            ConnectTunnelOutboundExchangeHandler(connector).handle(
                accepted = accepted(connectRequest(host = "secret.example", port = 443)),
                clientOutput = clientOutput,
            )

        val failed = assertIs<ConnectTunnelOutboundExchangeHandlingResult.ConnectionFailed>(result)
        assertEquals(ProxyServerFailure.OutboundConnectionTimeout, failed.failure)
        assertEquals(clientOutput.size(), failed.errorResponseBytesWritten)
        assertContains(clientOutput.toString(Charsets.UTF_8), "HTTP/1.1 504 Gateway Timeout")
        assertEquals(false, clientOutput.toString(Charsets.UTF_8).contains("secret.example"))
    }

    @Test
    fun `maps every connect open failure category to existing proxy server failure`() {
        assertEquals(
            ProxyServerFailure.SelectedRouteUnavailable,
            OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.DnsResolutionFailed,
            OutboundConnectTunnelOpenFailure.DnsResolutionFailed.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.OutboundConnectionFailed,
            OutboundConnectTunnelOpenFailure.OutboundConnectionFailed.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.OutboundConnectionTimeout,
            OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout.toProxyServerFailure(),
        )
    }

    @Test
    fun `closes opened origin when writing established response fails`() {
        val originInput = CloseTrackingInputStream(ByteArray(0))
        val originOutput = CloseTrackingOutputStream()
        val connector =
            RecordingConnectTunnelConnector(
                OutboundConnectTunnelOpenResult.Connected(
                    OutboundConnectTunnelConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 443,
                    ),
                ),
            )

        assertFailsWith<IOException> {
            ConnectTunnelOutboundExchangeHandler(connector).handle(
                accepted = accepted(connectRequest()),
                clientOutput = ThrowingWriteOutputStream(),
            )
        }

        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `rejects impossible result and connection values`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelOutboundExchangeHandlingResult.ConnectionFailed(
                failure = ProxyServerFailure.DnsResolutionFailed,
                errorResponseBytesWritten = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelOutboundExchangeHandlingResult.ConnectionFailed(
                failure = ProxyServerFailure.ClientDisconnected,
                errorResponseBytesWritten = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelOutboundExchangeHandlingResult.Established(
                connection =
                    OutboundConnectTunnelConnection(
                        input = ByteArrayInputStream(ByteArray(0)),
                        output = ByteArrayOutputStream(),
                        host = "origin.example",
                        port = 443,
                    ),
                host = "origin.example",
                port = 443,
                responseBytesWritten = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OutboundConnectTunnelConnection(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                host = "",
                port = 443,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OutboundConnectTunnelConnection(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                host = "origin.example",
                port = 0,
            )
        }
    }

    private fun connectRequest(
        host: String = "origin.example",
        port: Int = 443,
    ): ParsedHttpRequest = ParsedHttpRequest(
        request = ParsedProxyRequest.ConnectTunnel(host = host, port = port),
        headers = emptyMap(),
    )

    private fun accepted(request: ParsedHttpRequest): ProxyIngressStreamPreflightDecision.Accepted = ProxyIngressStreamPreflightDecision.Accepted(
        httpRequest = request,
        activeConnectionsAfterAdmission = 1,
        requiresAuditLog = false,
        headerBytesRead = 128,
    )

    private class RecordingConnectTunnelConnector(
        private val result: OutboundConnectTunnelOpenResult,
    ) : OutboundConnectTunnelConnector {
        val openedOrigins = mutableListOf<Pair<String, Int>>()

        override fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult {
            openedOrigins += request.host to request.port
            return result
        }
    }

    private class FailingConnectTunnelConnector : OutboundConnectTunnelConnector {
        override fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult = error("Origin must not be opened")
    }

    private class CloseTrackingInputStream(
        private val delegate: ByteArrayInputStream,
    ) : InputStream() {
        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))

        var wasClosed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(buffer, offset, length)

        override fun close() {
            wasClosed = true
            delegate.close()
        }
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private class ThrowingWriteOutputStream : OutputStream() {
        override fun write(value: Int): Unit = throw IOException("write failed")

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Unit = throw IOException("write failed")
    }

    private class ThrowingReadInputStream : InputStream() {
        override fun read(): Int = throw IOException("read failed")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw IOException("read failed")
    }

    private class ThrowingCloseTrackingInputStream : InputStream() {
        var wasClosed: Boolean = false
            private set

        override fun read(): Int = throw IOException("read failed")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw IOException("read failed")

        override fun close() {
            wasClosed = true
        }
    }
}
