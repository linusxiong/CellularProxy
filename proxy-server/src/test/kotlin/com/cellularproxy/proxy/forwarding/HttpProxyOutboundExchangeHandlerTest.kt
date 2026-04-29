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

class HttpProxyOutboundExchangeHandlerTest {
    @Test
    fun `opens origin connection and preserves stream forwarder result`() {
        val accepted =
            accepted(
                httpProxyRequest(
                    method = "GET",
                    originTarget = "/resource",
                ),
            )
        val clientInput = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val clientOutput = ByteArrayOutputStream()
        val originInputHeader = "HTTP/1.1 204 No Content\r\ncontent-length: 0\r\n\r\n"
        val originOutput = ByteArrayOutputStream()
        val originConnection =
            OutboundHttpOriginConnection(
                input = ByteArrayInputStream(originInputHeader.toByteArray(Charsets.US_ASCII)),
                output = originOutput,
                host = "origin.example",
                port = 80,
            )
        val connector = RecordingConnector(OutboundHttpOriginOpenResult.Connected(originConnection))

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientInput = clientInput,
                clientOutput = clientOutput,
            )

        assertEquals(listOf("origin.example" to 80), connector.openedOrigins)
        val forwarded = assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(result)
        assertEquals(
            HttpProxyStreamExchangeForwardingResult.Forwarded(
                host = "origin.example",
                port = 80,
                requestHeaderBytesWritten =
                    (
                        "GET /resource HTTP/1.1\r\n" +
                            "host: origin.example\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                requestBodyBytesWritten = 0,
                responseStatusCode = 204,
                responseHeaderBytesRead = originInputHeader.toByteArray(Charsets.UTF_8).size,
                responseHeaderBytesWritten =
                    (
                        "HTTP/1.1 204 No Content\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                responseBodyBytesWritten = 0,
                mustCloseClientConnection = false,
            ),
            forwarded.result,
        )
        assertEquals(
            "GET /resource HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "\r\n",
            originOutput.toString(Charsets.UTF_8),
        )
        assertEquals(
            "HTTP/1.1 204 No Content\r\n" +
                "\r\n",
            clientOutput.toString(Charsets.UTF_8),
        )
        assertEquals('N'.code, clientInput.read())
    }

    @Test
    fun `closes connected origin streams after successful exchange`() {
        val accepted = accepted(httpProxyRequest(method = "GET"))
        val originInput = CloseTrackingInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originOutput = CloseTrackingOutputStream()
        val connector =
            RecordingConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 80,
                    ),
                ),
            )

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientInput = ByteArrayInputStream(ByteArray(0)),
                clientOutput = ByteArrayOutputStream(),
            )

        assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(result)
        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `closes connected origin streams after exchange forwarding failure`() {
        val accepted =
            accepted(
                httpProxyRequest(headers = linkedMapOf("transfer-encoding" to listOf("chunked"))),
            )
        val originInput = CloseTrackingInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originOutput = CloseTrackingOutputStream()
        val connector =
            RecordingConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 80,
                    ),
                ),
            )

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientInput = ByteArrayInputStream("0\r\n\r\n".toByteArray(Charsets.US_ASCII)),
                clientOutput = ByteArrayOutputStream(),
            )

        val forwarded = assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(result)
        assertIs<HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed>(forwarded.result)
        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `origin close failure does not replace exchange result`() {
        val accepted = accepted(httpProxyRequest(method = "GET"))
        val originInput = CloseTrackingInputStream("HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originOutput = ThrowingCloseOutputStream()
        val connector =
            RecordingConnector(
                OutboundHttpOriginOpenResult.Connected(
                    OutboundHttpOriginConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 80,
                    ),
                ),
            )

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientInput = ByteArrayInputStream(ByteArray(0)),
                clientOutput = ByteArrayOutputStream(),
            )

        assertIs<HttpProxyOutboundExchangeHandlingResult.Forwarded>(result)
        assertEquals(true, originInput.wasClosed)
        assertEquals(true, originOutput.wasClosed)
    }

    @Test
    fun `returns unsupported for accepted CONNECT without opening origin or writing client output`() {
        val connector = RecordingConnector(OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.DnsResolutionFailed))
        val clientOutput = ByteArrayOutputStream()

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted =
                    accepted(
                        ParsedHttpRequest(
                            request = ParsedProxyRequest.ConnectTunnel("origin.example", 443),
                            headers = emptyMap(),
                        ),
                    ),
                clientInput = ByteArrayInputStream(ByteArray(0)),
                clientOutput = clientOutput,
            )

        assertEquals(
            HttpProxyOutboundExchangeHandlingResult.UnsupportedAcceptedRequest(
                HttpProxyOutboundExchangeUnsupportedReason.NotPlainHttpProxyRequest,
            ),
            result,
        )
        assertEquals(emptyList(), connector.openedOrigins)
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
    }

    @Test
    fun `returns unsupported for accepted management request without opening origin or writing client output`() {
        val connector = RecordingConnector(OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.DnsResolutionFailed))
        val clientOutput = ByteArrayOutputStream()

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
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
                clientInput = ByteArrayInputStream(ByteArray(0)),
                clientOutput = clientOutput,
            )

        assertEquals(
            HttpProxyOutboundExchangeHandlingResult.UnsupportedAcceptedRequest(
                HttpProxyOutboundExchangeUnsupportedReason.NotPlainHttpProxyRequest,
            ),
            result,
        )
        assertEquals(emptyList(), connector.openedOrigins)
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
    }

    @Test
    fun `maps outbound open failures to sanitized proxy error responses`() {
        val accepted = accepted(httpProxyRequest(originTarget = "/secret?token=raw-secret"))
        val clientInput = ByteArrayInputStream("body".toByteArray(Charsets.US_ASCII))
        val clientOutput = ByteArrayOutputStream()
        val connector =
            RecordingConnector(
                OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.DnsResolutionFailed),
            )

        val result =
            HttpProxyOutboundExchangeHandler(connector).handle(
                accepted = accepted,
                clientInput = clientInput,
                clientOutput = clientOutput,
            )

        val failed = assertIs<HttpProxyOutboundExchangeHandlingResult.ConnectionFailed>(result)
        assertEquals(ProxyServerFailure.DnsResolutionFailed, failed.failure)
        assertEquals(clientOutput.size(), failed.errorResponseBytesWritten)
        assertContains(clientOutput.toString(Charsets.UTF_8), "HTTP/1.1 502 Bad Gateway")
        assertContains(clientOutput.toString(Charsets.UTF_8), "Bad gateway\n")
        assertEquals(false, clientOutput.toString(Charsets.UTF_8).contains("raw-secret"))
        assertEquals(false, clientOutput.toString(Charsets.UTF_8).contains("origin.example"))
        assertEquals('b'.code, clientInput.read())
    }

    @Test
    fun `maps every outbound open failure category to existing proxy server failure`() {
        assertEquals(
            ProxyServerFailure.SelectedRouteUnavailable,
            OutboundHttpOriginOpenFailure.SelectedRouteUnavailable.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.DnsResolutionFailed,
            OutboundHttpOriginOpenFailure.DnsResolutionFailed.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.OutboundConnectionFailed,
            OutboundHttpOriginOpenFailure.OutboundConnectionFailed.toProxyServerFailure(),
        )
        assertEquals(
            ProxyServerFailure.OutboundConnectionTimeout,
            OutboundHttpOriginOpenFailure.OutboundConnectionTimeout.toProxyServerFailure(),
        )
    }

    @Test
    fun `rejects impossible result values`() {
        assertFailsWith<IllegalArgumentException> {
            HttpProxyOutboundExchangeHandlingResult.ConnectionFailed(
                failure = ProxyServerFailure.DnsResolutionFailed,
                errorResponseBytesWritten = -1,
            )
        }
    }

    @Test
    fun `rejects impossible origin connection values`() {
        assertFailsWith<IllegalArgumentException> {
            OutboundHttpOriginConnection(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                host = "",
                port = 80,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OutboundHttpOriginConnection(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                host = "origin.example",
                port = 0,
            )
        }
    }

    private fun httpProxyRequest(
        method: String = "GET",
        host: String = "origin.example",
        port: Int = 80,
        originTarget: String = "/resource",
        headers: Map<String, List<String>> = emptyMap(),
    ): ParsedHttpRequest = ParsedHttpRequest(
        request =
            ParsedProxyRequest.HttpProxy(
                method = method,
                host = host,
                port = port,
                originTarget = originTarget,
            ),
        headers = headers,
    )

    private fun accepted(request: ParsedHttpRequest): ProxyIngressStreamPreflightDecision.Accepted = ProxyIngressStreamPreflightDecision.Accepted(
        httpRequest = request,
        activeConnectionsAfterAdmission = 1,
        requiresAuditLog = false,
        headerBytesRead = 128,
    )

    private class RecordingConnector(
        private val result: OutboundHttpOriginOpenResult,
    ) : OutboundHttpOriginConnector {
        val openedOrigins = mutableListOf<Pair<String, Int>>()

        override fun open(request: ParsedProxyRequest.HttpProxy): OutboundHttpOriginOpenResult {
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
    }

    private class ThrowingCloseOutputStream : OutputStream() {
        var wasClosed: Boolean = false
            private set

        override fun write(value: Int) = Unit

        override fun close() {
            wasClosed = true
            throw IOException("close failed")
        }
    }
}
