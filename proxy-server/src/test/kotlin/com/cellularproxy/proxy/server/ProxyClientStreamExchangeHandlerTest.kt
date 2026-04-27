package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.ConnectTunnelBidirectionalRelayResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelRelayDirection
import com.cellularproxy.proxy.forwarding.ConnectTunnelStreamRelayResult
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnection
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnection
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiHandlerException
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamAuditEvent
import com.cellularproxy.proxy.management.ManagementApiStreamAuditOutcome
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProxyClientStreamExchangeHandlerTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")
    private val config =
        ProxyIngressPreflightConfig(
            connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 2),
            requestAdmission =
                ProxyRequestAdmissionConfig(
                    proxyAuthentication =
                        ProxyAuthenticationConfig(
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
        val handler =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
            )

        val result =
            handler.handle(
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
        val request =
            (
                "GET http://origin.example/resource HTTP/1.1\r\n" +
                    "Host: origin.example\r\n" +
                    "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val originOutput = ByteArrayOutputStream()
        val httpConnector =
            RecordingHttpConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                        output = originOutput,
                        host = "origin.example",
                        port = 80,
                    ),
                ),
            )

        val result =
            handler(
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
    fun `tracks admitted plain HTTP proxy exchange only while forwarding runs`() {
        val request =
            (
                "GET http://origin.example/resource HTTP/1.1\r\n" +
                    "Host: origin.example\r\n" +
                    "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val tracker = ProxyTrafficActivityTracker()
        val observedActiveProxyExchanges = mutableListOf<Long>()
        val httpConnector =
            RecordingHttpConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                        output = ByteArrayOutputStream(),
                        host = "origin.example",
                        port = 80,
                    ),
                ),
                onOpen = {
                    observedActiveProxyExchanges += tracker.activeProxyExchanges
                },
            )

        val result =
            handler(
                httpConnector = httpConnector,
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
                proxyActivityTracker = tracker,
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
            )

        assertIs<ProxyClientStreamExchangeHandlingResult.HttpProxyHandled>(result)
        assertEquals(listOf(1L), observedActiveProxyExchanges)
        assertEquals(0, tracker.activeProxyExchanges)
    }

    @Test
    fun `dispatches admitted management streams to management handler`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val metricEvents = mutableListOf<ProxyTrafficMetricsEvent>()
        val managementHandler =
            RecordingManagementHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
            )

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = managementHandler,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = output,
                    ),
                recordMetricEvent = { metricEvents.add(it) },
            )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result)
        assertEquals(202, responded.statusCode)
        assertEquals(ManagementApiOperation.ServiceStop, responded.operation)
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
    fun `records local high impact management response audit event`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler =
                    RecordingManagementHandler(
                        ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
                    ),
                recordManagementAudit = auditEvents::add,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = ByteArrayOutputStream(),
                    ),
            )

        assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiStreamAuditOutcome.Responded,
                    statusCode = 202,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `records local high impact route rejection audit event with attempted operation`() {
        val request =
            (
                "POST /api/service/stop?reason=local HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
                recordManagementAudit = auditEvents::add,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = ByteArrayOutputStream(),
                    ),
            )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result)
        assertEquals(400, responded.statusCode)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiStreamAuditOutcome.RouteRejected,
                    statusCode = 400,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `records local high impact authorization rejection audit event`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
                recordManagementAudit = auditEvents::add,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = output,
                    ),
            )

        val rejected = assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(result)
        assertEquals(output.size(), rejected.responseBytesWritten)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 401 Unauthorized")
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = null,
                    outcome = ManagementApiStreamAuditOutcome.AuthorizationRejected,
                    statusCode = 401,
                    disposition = null,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `records local high impact handler failure audit event before rethrowing`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()
        val failure = IOException("runtime unavailable")

        val thrown =
            assertFailsWith<ManagementApiHandlerException> {
                handler(
                    httpConnector = ThrowingHttpConnector(),
                    connectConnector = ThrowingConnectConnector(),
                    managementHandler = ManagementApiHandler { throw failure },
                    recordManagementAudit = auditEvents::add,
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client =
                        ProxyClientStreamConnection(
                            input = ByteArrayInputStream(request),
                            output = ByteArrayOutputStream(),
                        ),
                )
            }

        assertSame(failure, thrown.cause)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiStreamAuditOutcome.HandlerFailed,
                    statusCode = null,
                    disposition = null,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `local management audit recorder failure does not replace command response`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler =
                    RecordingManagementHandler(
                        ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
                    ),
                recordManagementAudit = { throw IllegalStateException("audit unavailable") },
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = output,
                    ),
            )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        assertEquals(202, assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result).statusCode)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 202 Accepted")
    }

    @Test
    fun `records local high impact management response audit before response write failure`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()
        val writeFailure = IOException("client disconnected")

        val thrown =
            assertFailsWith<IOException> {
                handler(
                    httpConnector = ThrowingHttpConnector(),
                    connectConnector = ThrowingConnectConnector(),
                    managementHandler =
                        RecordingManagementHandler(
                            ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
                        ),
                    recordManagementAudit = auditEvents::add,
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client =
                        ProxyClientStreamConnection(
                            input = ByteArrayInputStream(request),
                            output = ThrowingOutputStream(writeFailure),
                        ),
                )
            }

        assertSame(writeFailure, thrown)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiStreamAuditOutcome.Responded,
                    statusCode = 202,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `records local high impact route rejection audit before response write failure`() {
        val request =
            (
                "POST /api/service/stop?reason=local HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()
        val writeFailure = IOException("client disconnected")

        val thrown =
            assertFailsWith<IOException> {
                handler(
                    httpConnector = ThrowingHttpConnector(),
                    connectConnector = ThrowingConnectConnector(),
                    managementHandler = ThrowingManagementHandler(),
                    recordManagementAudit = auditEvents::add,
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client =
                        ProxyClientStreamConnection(
                            input = ByteArrayInputStream(request),
                            output = ThrowingOutputStream(writeFailure),
                        ),
                )
            }

        assertSame(writeFailure, thrown)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceStop,
                    outcome = ManagementApiStreamAuditOutcome.RouteRejected,
                    statusCode = 400,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `records local high impact authorization rejection audit before response write failure`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()
        val writeFailure = IOException("client disconnected")

        val thrown =
            assertFailsWith<IOException> {
                handler(
                    httpConnector = ThrowingHttpConnector(),
                    connectConnector = ThrowingConnectConnector(),
                    managementHandler = ThrowingManagementHandler(),
                    recordManagementAudit = auditEvents::add,
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client =
                        ProxyClientStreamConnection(
                            input = ByteArrayInputStream(request),
                            output = ThrowingOutputStream(writeFailure),
                        ),
                )
            }

        assertSame(writeFailure, thrown)
        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = null,
                    outcome = ManagementApiStreamAuditOutcome.AuthorizationRejected,
                    statusCode = 401,
                    disposition = null,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `does not track management exchanges as active proxy traffic`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val tracker = ProxyTrafficActivityTracker()
        val observedActiveProxyExchanges = mutableListOf<Long>()
        val managementHandler =
            RecordingManagementHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
                onHandle = {
                    observedActiveProxyExchanges += tracker.activeProxyExchanges
                },
            )

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = managementHandler,
                proxyActivityTracker = tracker,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream(request),
                        output = output,
                    ),
            )

        assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        assertEquals(listOf(0L), observedActiveProxyExchanges)
        assertEquals(0, tracker.activeProxyExchanges)
    }

    @Test
    fun `does not track preflight rejections as active proxy traffic`() {
        val tracker = ProxyTrafficActivityTracker()
        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
                proxyActivityTracker = tracker,
            ).handle(
                config = config,
                activeConnections = 2,
                client =
                    ProxyClientStreamConnection(
                        input = ByteArrayInputStream("not consumed".toByteArray(Charsets.US_ASCII)),
                        output = ByteArrayOutputStream(),
                    ),
            )

        assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(result)
        assertEquals(0, tracker.activeProxyExchanges)
    }

    @Test
    fun `continues handling when metric callback throws an ordinary exception`() {
        val request =
            (
                "POST /api/service/stop HTTP/1.1\r\n" +
                    "Host: phone.local\r\n" +
                    "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val metricEvents = mutableListOf<ProxyTrafficMetricsEvent>()
        val managementHandler =
            RecordingManagementHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
            )

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = ThrowingConnectConnector(),
                managementHandler = managementHandler,
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
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
        val request =
            (
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
        val connectConnector =
            RecordingConnectConnector(
                OutboundConnectTunnelOpenResult.Connected(
                    OutboundConnectTunnelConnection(
                        input = ByteArrayInputStream("origin bytes".toByteArray(Charsets.US_ASCII)),
                        output = originOutput,
                        host = "origin.example",
                        port = 443,
                    ),
                ),
            )

        val result =
            handler(
                httpConnector = ThrowingHttpConnector(),
                connectConnector = connectConnector,
                managementHandler = ThrowingManagementHandler(),
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
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
        val request =
            (
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
        val httpConnector =
            RecordingHttpConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = ByteArrayInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                        output = originOutput,
                        host = "origin.example",
                        port = 80,
                    ),
                ),
            )

        val result =
            handler(
                httpConnector = httpConnector,
                connectConnector = ThrowingConnectConnector(),
                managementHandler = ThrowingManagementHandler(),
            ).handle(
                config = config,
                activeConnections = 0,
                client =
                    ProxyClientStreamConnection(
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
        val request =
            (
                "GET http://origin.example/resource HTTP/1.1\r\n" +
                    "Host: origin.example\r\n" +
                    "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                    "\r\n" +
                    "BODY"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val connector =
            RecordingHttpConnector(
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
        val request =
            (
                "CONNECT origin.example:443 HTTP/1.1\r\n" +
                    "Host: origin.example:443\r\n" +
                    "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                    "\r\n" +
                    "client bytes"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(request)
        val output = ByteArrayOutputStream()
        val connector =
            RecordingConnectConnector(
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
        proxyActivityTracker: ProxyTrafficActivityTracker = ProxyTrafficActivityTracker(),
        recordManagementAudit: (ManagementApiStreamAuditEvent) -> Unit = {},
    ): ProxyClientStreamExchangeHandler =
        ProxyClientStreamExchangeHandler(
            httpConnector = httpConnector,
            connectConnector = connectConnector,
            managementHandler = managementHandler,
            proxyActivityTracker = proxyActivityTracker,
            recordManagementAudit = recordManagementAudit,
        )

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }

    private class RecordingHttpConnector(
        private val result: OutboundHttpOriginOpenResult,
        private val onOpen: () -> Unit = {},
    ) : OutboundHttpOriginConnector {
        val openedOrigins = mutableListOf<Pair<String, Int>>()

        override fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult {
            openedOrigins += request.host to request.port
            onOpen()
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
        private val onHandle: () -> Unit = {},
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            onHandle()
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

    private class CloseTrackingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var wasClosed: Boolean = false
            private set

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            delegate.write(buffer, offset, length)
        }

        override fun close() {
            wasClosed = true
            delegate.close()
        }

        override fun toString(): String = delegate.toString(Charsets.UTF_8)
    }

    private class ThrowingOutputStream(
        private val failure: IOException,
    ) : OutputStream() {
        override fun write(value: Int): Unit = throw failure

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Unit = throw failure
    }
}

private const val MANAGEMENT_TOKEN = "management-token"
