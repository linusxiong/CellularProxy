package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelConnector
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginConnector
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiHandlerException
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
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
import kotlin.test.assertTrue

class ProxyClientStreamExchangeLifecycleTest {
    private val config =
        com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig(
            connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 2),
            requestAdmission =
                ProxyRequestAdmissionConfig(
                    proxyAuthentication =
                        ProxyAuthenticationConfig(
                            authEnabled = false,
                            credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                        ),
                    managementApiToken = MANAGEMENT_TOKEN,
                ),
        )

    @Test
    fun `closes client streams after successful exchange handling`() {
        val input =
            CloseTrackingInputStream(
                (
                    "GET /health HTTP/1.1\r\n" +
                        "Host: phone.local\r\n" +
                        "\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
        val output = CloseTrackingOutputStream()

        val result =
            handler(
                managementHandler =
                    StaticManagementHandler(
                        ManagementApiResponse.json(statusCode = 200, body = """{"ok":true}"""),
                    ),
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
            )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result)
        assertContains(output.toString(), "HTTP/1.1 200 OK")
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    @Test
    fun `closes client streams after preflight rejection`() {
        val input = CloseTrackingInputStream("not consumed".toByteArray(Charsets.US_ASCII))
        val output = CloseTrackingOutputStream()

        val result =
            handler(
                managementHandler = ThrowingManagementHandler(IOException("management must not be called")),
            ).handle(
                config = config,
                activeConnections = 2,
                client = ProxyClientStreamConnection(input = input, output = output),
            )

        assertIs<ProxyClientStreamExchangeHandlingResult.PreflightRejected>(result)
        assertContains(output.toString(), "HTTP/1.1 503 Service Unavailable")
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    @Test
    fun `closes client streams when exchange handling throws`() {
        val input =
            CloseTrackingInputStream(
                (
                    "GET /api/status HTTP/1.1\r\n" +
                        "Host: phone.local\r\n" +
                        "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                        "\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
        val output = CloseTrackingOutputStream()

        val failure =
            assertFailsWith<ManagementApiHandlerException> {
                handler(
                    managementHandler = ThrowingManagementHandler(IOException("handler unavailable")),
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client = ProxyClientStreamConnection(input = input, output = output),
                )
            }

        assertIs<IOException>(failure.cause)
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    @Test
    fun `ordinary client close failures do not replace exchange handling failure`() {
        val input =
            CloseTrackingInputStream(
                (
                    "GET /api/status HTTP/1.1\r\n" +
                        "Host: phone.local\r\n" +
                        "Authorization: Bearer $MANAGEMENT_TOKEN\r\n" +
                        "\r\n"
                ).toByteArray(Charsets.US_ASCII),
                failOnClose = true,
            )
        val output = CloseTrackingOutputStream(failOnClose = true)

        val failure =
            assertFailsWith<ManagementApiHandlerException> {
                handler(
                    managementHandler = ThrowingManagementHandler(IOException("handler unavailable")),
                ).handle(
                    config = config,
                    activeConnections = 0,
                    client = ProxyClientStreamConnection(input = input, output = output),
                )
            }

        assertIs<IOException>(failure.cause)
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    @Test
    fun `ordinary client close failures do not replace exchange result`() {
        val input =
            CloseTrackingInputStream(
                (
                    "GET /health HTTP/1.1\r\n" +
                        "Host: phone.local\r\n" +
                        "\r\n"
                ).toByteArray(Charsets.US_ASCII),
                failOnClose = true,
            )
        val output = CloseTrackingOutputStream(failOnClose = true)

        val result =
            handler(
                managementHandler =
                    StaticManagementHandler(
                        ManagementApiResponse.json(statusCode = 200, body = """{"ok":true}"""),
                    ),
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
            )

        val handled = assertIs<ProxyClientStreamExchangeHandlingResult.ManagementHandled>(result)
        assertEquals(200, assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(handled.result).statusCode)
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    @Test
    fun `passes HTTP tunables through to stream exchange handler before consuming client bytes`() {
        val input =
            CloseTrackingInputStream(
                (
                    "GET /health HTTP/1.1\r\n" +
                        "Host: phone.local\r\n" +
                        "\r\n"
                ).toByteArray(Charsets.US_ASCII),
            )
        val output = CloseTrackingOutputStream()

        assertFailsWith<IllegalArgumentException> {
            handler(
                managementHandler =
                    StaticManagementHandler(
                        ManagementApiResponse.json(statusCode = 200, body = """{"ok":true}"""),
                    ),
            ).handle(
                config = config,
                activeConnections = 0,
                client = ProxyClientStreamConnection(input = input, output = output),
                httpBufferSize = 0,
            )
        }

        assertEquals('G'.code, input.read())
        assertEquals("", output.toString())
        assertTrue(input.wasClosed)
        assertTrue(output.wasClosed)
    }

    private fun handler(managementHandler: ManagementApiHandler): ProxyClientStreamExchangeHandler =
        ProxyClientStreamExchangeHandler(
            httpConnector = ThrowingHttpConnector(),
            connectConnector = ThrowingConnectConnector(),
            managementHandler = managementHandler,
        )

    private class StaticManagementHandler(
        private val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        override fun handle(operation: ManagementApiOperation): ManagementApiResponse = response
    }

    private class ThrowingManagementHandler(
        private val failure: IOException,
    ) : ManagementApiHandler {
        override fun handle(operation: ManagementApiOperation): ManagementApiResponse = throw failure
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

    private class CloseTrackingInputStream(
        bytes: ByteArray,
        private val failOnClose: Boolean = false,
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
            if (failOnClose) {
                throw IOException("input close failed")
            }
        }
    }

    private class CloseTrackingOutputStream(
        private val failOnClose: Boolean = false,
    ) : OutputStream() {
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
            if (failOnClose) {
                throw IOException("output close failed")
            }
        }

        override fun toString(): String = delegate.toString(Charsets.UTF_8)
    }
}

private const val MANAGEMENT_TOKEN = "management-token"
