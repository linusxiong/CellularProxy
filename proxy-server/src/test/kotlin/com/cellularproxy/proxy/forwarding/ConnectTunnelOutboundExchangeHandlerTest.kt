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
    fun `opens tunnel origin and writes connection established response`() {
        val accepted = accepted(connectRequest(host = "origin.example", port = 443))
        val clientOutput = ByteArrayOutputStream()
        val originInput = CloseTrackingInputStream(ByteArray(0))
        val originOutput = CloseTrackingOutputStream()
        val originConnection = OutboundConnectTunnelConnection(
            input = originInput,
            output = originOutput,
            host = "origin.example",
            port = 443,
        )
        val connector = RecordingConnectTunnelConnector(
            OutboundConnectTunnelOpenResult.Connected(originConnection),
        )

        val result = ConnectTunnelOutboundExchangeHandler(connector).handle(
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
        val connector = RecordingConnectTunnelConnector(
            OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.DnsResolutionFailed),
        )
        val clientOutput = ByteArrayOutputStream()

        val httpResult = ConnectTunnelOutboundExchangeHandler(connector).handle(
            accepted = accepted(
                ParsedHttpRequest(
                    request = ParsedProxyRequest.HttpProxy(
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

        val managementResult = ConnectTunnelOutboundExchangeHandler(connector).handle(
            accepted = accepted(
                ParsedHttpRequest(
                    request = ParsedProxyRequest.Management(
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
        val connector = RecordingConnectTunnelConnector(
            OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.OutboundConnectionTimeout),
        )
        val clientOutput = ByteArrayOutputStream()

        val result = ConnectTunnelOutboundExchangeHandler(connector).handle(
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
        val connector = RecordingConnectTunnelConnector(
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
                connection = OutboundConnectTunnelConnection(
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
    ): ParsedHttpRequest =
        ParsedHttpRequest(
            request = ParsedProxyRequest.ConnectTunnel(host = host, port = port),
            headers = emptyMap(),
        )

    private fun accepted(request: ParsedHttpRequest): ProxyIngressStreamPreflightDecision.Accepted =
        ProxyIngressStreamPreflightDecision.Accepted(
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

    private class CloseTrackingInputStream(
        private val delegate: ByteArrayInputStream,
    ) : InputStream() {
        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))

        var wasClosed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            wasClosed = true
            delegate.close()
        }
    }

    private class CloseTrackingOutputStream : OutputStream() {
        var wasClosed: Boolean = false
            private set

        override fun write(value: Int) = Unit

        override fun close() {
            wasClosed = true
        }
    }

    private class ThrowingWriteOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw IOException("write failed")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            throw IOException("write failed")
        }
    }
}
