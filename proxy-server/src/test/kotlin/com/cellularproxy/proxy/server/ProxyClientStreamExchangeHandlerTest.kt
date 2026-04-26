package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnection
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnection
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelBidirectionalRelayResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelStreamRelayResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelRelayDirection
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyClientStreamExchangeHandlerTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")
    private val config = ProxyIngressPreflightConfig(
        connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 2),
        requestAdmission = ProxyRequestAdmissionConfig(
            proxyAuthentication = ProxyAuthenticationConfig(
                authEnabled = true,
                credential = credential,
            ),
            managementApiToken = MANAGEMENT_TOKEN,
        ),
    )

    @Test
    fun `writes preflight rejection response without dispatching accepted handlers`() {
        val input = ByteArrayInputStream("not consumed".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()
        val handler = handler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = ThrowingConnectConnector(),
            managementHandler = ThrowingManagementHandler(),
        )

        val result = handler.handle(
            config = config,
            activeConnections = 2,
            client = ProxyClientStreamConnection(input = input, output = output),
        )

        val rejected = assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(result)
        assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
        assertEquals(0, rejected.headerBytesRead)
        assertFalse(rejected.requiresAuditLog)
        assertEquals(output.size(), rejected.responseBytesWritten)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 503 Service Unavailable")
        assertEquals('n'.code, input.read())
    }

    @Test
    fun `dispatches admitted plain HTTP proxy streams to outbound HTTP handler`() {
        val request = (
            "GET http://origin.example/resource HTTP/1.1\r\n" +
                "Host: origin.example\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val originOutput = ByteArrayOutputStream()
        val httpConnector = RecordingHttpConnector(
            OutboundHttpOriginOpenResult.Connected(
                OutboundHttpOriginConnection(
                    input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                    output = originOutput,
                    host = "origin.example",
                    port = 80,
                ),
            ),
        )

        val result = handler(
            httpConnector = httpConnector,
            connectConnector = ThrowingConnectConnector(),
            managementHandler = ThrowingManagementHandler(),
        ).handle(
            config = config,
            activeConnections = 0,
            client = ProxyClientStreamConnection(input = input, output = output),
        )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.HttpProxyHandled>(result)
        assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(handled.result)
        assertEquals(listOf("origin.example" to 80), httpConnector.openedOrigins)
        assertEquals(
            "GET /resource HTTP/1.1\r\nhost: origin.example\r\n\r\n",
            originOutput.toString(Charsets.UTF_8),
        )
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 204 No Content")
    }

    @Test
    fun `dispatches admitted management streams to management handler`() {
        val request = (
            "POST /api/service/stop HTTP/1.1\r\n" +
                "Host: phone.local\r\n" +
                "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val metricEvents = mutableListOf<ProxyTrafficMetricsEvent>()
        val managementHandler = RecordingManagementHandler(
            ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
        )

        val result = handler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = ThrowingConnectConnector(),
            managementHandler = managementHandler,
        ).handle(
            config = config,
            activeConnections = 0,
            client = ProxyClientStreamConnection(
                input = ByteArrayInputStream(request),
                output = output,
            ),
            recordMetricEvent = { metricEvents.add(it) },
        )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result)
        assertEquals(202, responded.statusCode)
        assertTrue(responded.requiresAuditLog)
        assertEquals(ManagementApiStreamExchangeDisposition.Routed, responded.disposition)
        assertEquals(listOf(ManagementApiOperation.ServiceStop), managementHandler.operations)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 202 Accepted")
        assertEquals(
            listOf(
                ProxyTrafficMetricsEvent.ConnectionAccepted,
                ProxyTrafficMetricsEvent.BytesReceived(request.size.toLong()),
                ProxyTrafficMetricsEvent.BytesSent(responded.responseBytesWritten.toLong()),
                ProxyTrafficMetricsEvent.ConnectionClosed,
            ),
            metricEvents,
        )
    }

    @Test
    fun `continues handling when metric callback throws an ordinary exception`() {
        val request = (
            "POST /api/service/stop HTTP/1.1\r\n" +
                "Host: phone.local\r\n" +
                "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val metricEvents = mutableListOf<ProxyTrafficMetricsEvent>()
        val managementHandler = RecordingManagementHandler(
            ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
        )

        val result = handler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = ThrowingConnectConnector(),
            managementHandler = managementHandler,
        ).handle(
            config = config,
            activeConnections = 0,
            client = ProxyClientStreamConnection(
                input = ByteArrayInputStream(request),
                output = output,
            ),
            recordMetricEvent = { event ->
                metricEvents.add(event)
                if (event is ProxyTrafficMetricsEvent.BytesReceived) {
                    throw IllegalStateException("metrics sink unavailable")
                }
            },
        )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result)
        assertEquals(202, responded.statusCode)
        assertEquals(listOf(ManagementApiOperation.ServiceStop), managementHandler.operations)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 202 Accepted")
        assertEquals(
            listOf(
                ProxyTrafficMetricsEvent.ConnectionAccepted,
                ProxyTrafficMetricsEvent.BytesReceived(request.size.toLong()),
                ProxyTrafficMetricsEvent.BytesSent(responded.responseBytesWritten.toLong()),
                ProxyTrafficMetricsEvent.ConnectionClosed,
            ),
            metricEvents,
        )
    }

    @Test
    fun `dispatches admitted CONNECT streams to tunnel relay handler with client shutdown hooks`() {
        val request = (
            "CONNECT origin.example:443 HTTP/1.1\r\n" +
                "Host: origin.example:443\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n" +
                "client bytes"
            ).toByteArray(Charsets.US_ASCII)
        val input = CloseTrackingInputStream(request)
        val output = CloseTrackingOutputStream()
        var clientInputShutdowns = 0
        var clientOutputShutdowns = 0
        val originOutput = ByteArrayOutputStream()
        val connectConnector = RecordingConnectConnector(
            OutboundConnectTunnelOpenResult.Connected(
                OutboundConnectTunnelConnection(
                    input = ByteArrayInputStream("origin bytes".toByteArray(Charsets.US_ASCII)),
                    output = originOutput,
                    host = "origin.example",
                    port = 443,
                ),
            ),
        )

        val result = handler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = connectConnector,
            managementHandler = ThrowingManagementHandler(),
        ).handle(
            config = config,
            activeConnections = 0,
            client = ProxyClientStreamConnection(
                input = input,
                output = output,
                shutdownInputAction = { clientInputShutdowns += 1 },
                shutdownOutputAction = { clientOutputShutdowns += 1 },
            ),
            connectRelayBufferSize = 4,
        )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled>(result)
        val relayed = assertIs<ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed>(handled.result)
        val relay = assertIs<ConnectTunnelBidirectionalRelayResult.Completed>(relayed.relayResult)
        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 12,
            ),
            relay.clientToOrigin,
        )
        assertEquals(listOf("origin.example" to 443), connectConnector.openedOrigins)
        assertEquals("client bytes", originOutput.toString(Charsets.UTF_8))
        assertContains(output.toString(), "HTTP/1.1 200 Connection Established")
        assertContains(output.toString(), "origin bytes")
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
        assertEquals(1, clientInputShutdowns)
        assertEquals(1, clientOutputShutdowns)
    }

    @Test
    fun `clears header read timeout after accepting request before forwarding body`() {
        val request = (
            "POST http://origin.example/upload HTTP/1.1\r\n" +
                "Host: origin.example\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "Content-Length: 4\r\n" +
                "\r\n" +
                "BODY"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val timeoutValues = mutableListOf<Int>()
        val originOutput = ByteArrayOutputStream()
        val httpConnector = RecordingHttpConnector(
            OutboundHttpOriginOpenResult.Connected(
                OutboundHttpOriginConnection(
                    input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                    output = originOutput,
                    host = "origin.example",
                    port = 80,
                ),
            ),
        )

        val result = handler(
            httpConnector = httpConnector,
            connectConnector = ThrowingConnectConnector(),
            managementHandler = ThrowingManagementHandler(),
        ).handle(
            config = config,
            activeConnections = 0,
            client = ProxyClientStreamConnection(
                input = input,
                output = output,
                setReadTimeoutMillisAction = { timeoutValues.add(it) },
            ),
        )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.HttpProxyHandled>(result)
        assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(handled.result)
        assertEquals(listOf(0), timeoutValues)
        assertContains(originOutput.toString(Charsets.UTF_8), "\r\n\r\nBODY")
    }

    @Test
    fun `rejects invalid HTTP tunables before consuming client bytes or opening origin`() {
        val request = (
            "GET http://origin.example/resource HTTP/1.1\r\n" +
                "Host: origin.example\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n" +
                "BODY"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val connector = RecordingHttpConnector(
            OutboundHttpOriginOpenResult.Connected(
                OutboundHttpOriginConnection(
                    input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                    output = ByteArrayOutputStream(),
                    host = "origin.example",
                    port = 80,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            handler(
                httpConnector = connector,
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
                httpBufferSize = 0,
            )
        }

        assertEquals('G'.code, input.read())
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals(emptyList(), connector.openedOrigins)
    }

    @Test
    fun `rejects invalid CONNECT relay tunables before consuming client bytes or opening origin`() {
        val request = (
            "CONNECT origin.example:443 HTTP/1.1\r\n" +
                "Host: origin.example:443\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "\r\n" +
                "client bytes"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val connector = RecordingConnectConnector(
            OutboundConnectTunnelOpenResult.Connected(
                OutboundConnectTunnelConnection(
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = ByteArrayOutputStream(),
                    host = "origin.example",
                    port = 443,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = connector,
                managementHandler = ThrowingManagementHandler(),
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
                connectRelayBufferSize = 0,
            )
        }

        assertEquals('C'.code, input.read())
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals(emptyList(), connector.openedOrigins)
    }

    private fun handler(
        httpConnector: OutboundHttpOriginConnector,
        connectConnector: OutboundConnectTunnelConnector,
        managementHandler: ManagementApiHandler,
    ): ProxyClientStreamExchangeHandler =
        ProxyClientStreamExchangeHandler(
            httpConnector = httpConnector,
            connectConnector = connectConnector,
            managementHandler = managementHandler,
        )

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }

    private class RecordingHttpConnector(
        private val result: OutboundHttpOriginOpenResult,
    ) : OutboundHttpOriginConnector {
        val openedOrigins = mutableListOf<Pair<String, Int>>()

        override fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult {
            openedOrigins += request.host to request.port
            return result
        }
    }

    private class RecordingConnectConnector(
        private val result: OutboundConnectTunnelOpenResult,
    ) : OutboundConnectTunnelConnector {
        val openedOrigins = mutableListOf<Pair<String, Int>>()

        override fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult {
            openedOrigins += request.host to request.port
            return result
        }
    }

    private class RecordingManagementHandler(
        private val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            return response
        }
    }

    private class ThrowingHttpConnector : OutboundHttpOriginConnector {
        override fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult {
            error("HTTP connector must not be called")
        }
    }

    private class ThrowingConnectConnector : OutboundConnectTunnelConnector {
        override fun open(request: ParsedProxyRequest.ConnectTunnel): OutboundConnectTunnelOpenResult {
            error("CONNECT connector must not be called")
        }
    }

    private class ThrowingManagementHandler : ManagementApiHandler {
        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            error("Management handler must not be called")
        }
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
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
        private val delegate = ByteArrayOutputStream()
        var wasClosed: Boolean = false
            private set

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
        }

        override fun close() {
            wasClosed = true
            delegate.close()
        }

        override fun toString(): String = delegate.toString(Charsets.UTF_8)
    }
}

private const val MANAGEMENT_TOKEN = "management-token"
