package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpRequestBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpProxyStreamExchangeForwarderTest {
    @Test
    fun `forwards accepted fixed-length request and fixed-length origin response`() {
        val accepted = accepted(
            httpProxyRequest(
                method = "POST",
                originTarget = "/upload",
                headers = linkedMapOf(
                    "content-length" to listOf("4"),
                    "proxy-authorization" to listOf("Basic secret"),
                ),
            ),
        )
        val clientInput = ByteArrayInputStream("dataNEXT".toByteArray(Charsets.US_ASCII))
        val originInputHeader = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\nconnection: close\r\n\r\n"
        val originInput = ByteArrayInputStream((originInputHeader + "OKNEXT").toByteArray(Charsets.US_ASCII))
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
            bufferSize = 2,
        )

        assertEquals(
            HttpProxyStreamExchangeForwardingResult.Forwarded(
                host = "origin.example",
                port = 80,
                requestHeaderBytesWritten = (
                    "POST /upload HTTP/1.1\r\n" +
                        "host: origin.example\r\n" +
                        "content-length: 4\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                requestBodyBytesWritten = 4,
                responseStatusCode = 200,
                responseHeaderBytesRead = originInputHeader.toByteArray(Charsets.UTF_8).size,
                responseHeaderBytesWritten = (
                    "HTTP/1.1 200 OK\r\n" +
                        "content-length: 2\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                responseBodyBytesWritten = 2,
                mustCloseClientConnection = false,
            ),
            result,
        )
        assertEquals(
            "POST /upload HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "content-length: 4\r\n" +
                "\r\n" +
                "data",
            originOutput.toString(Charsets.UTF_8),
        )
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 2\r\n" +
                "\r\n" +
                "OK",
            clientOutput.toString(Charsets.UTF_8),
        )
        assertEquals('N'.code, clientInput.read())
        assertEquals('N'.code, originInput.read())
    }

    @Test
    fun `returns request forwarding failure without reading origin response`() {
        val accepted = accepted(
            httpProxyRequest(headers = linkedMapOf("transfer-encoding" to listOf("chunked"))),
        )
        val clientInput = ByteArrayInputStream("4\r\ndata\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originInput = ByteArrayInputStream("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
        )

        val failed = assertIs<HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed>(result)
        assertEquals(
            HttpProxyRequestStreamForwardingResult.Rejected(
                HttpProxyRequestStreamForwardingRejectionReason.BodyFramingRejected(
                    HttpRequestBodyFramingRejectionReason.UnsupportedTransferEncoding,
                ),
            ),
            failed.result,
        )
        assertEquals(emptyList(), originOutput.toByteArray().toList())
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
        assertEquals('4'.code, clientInput.read())
        assertEquals('H'.code, originInput.read())
    }

    @Test
    fun `returns origin response preflight rejection after forwarding request but before client write`() {
        val accepted = accepted(httpProxyRequest(method = "GET", originTarget = "/resource"))
        val clientInput = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val originInputBytes = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n".toByteArray(Charsets.US_ASCII)
        val originInput = ByteArrayInputStream(originInputBytes)
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
        )

        assertEquals(
            HttpProxyStreamExchangeForwardingResult.OriginResponsePreflightRejected(
                reason = OriginHttpResponseStreamPreflightRejectionReason.IncompleteHeaderBlock,
                responseHeaderBytesRead = originInputBytes.size,
            ),
            result,
        )
        assertEquals(
            "GET /resource HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "\r\n",
            originOutput.toString(Charsets.UTF_8),
        )
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
        assertEquals('N'.code, clientInput.read())
    }

    @Test
    fun `returns request body copy failure after writing partial request and does not read origin response`() {
        val accepted = accepted(httpProxyRequest(headers = linkedMapOf("content-length" to listOf("4"))))
        val clientInput = ByteArrayInputStream("bo".toByteArray(Charsets.US_ASCII))
        val originInput = ByteArrayInputStream("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
        )

        val failed = assertIs<HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed>(result)
        assertIs<HttpProxyRequestStreamForwardingResult.BodyCopyFailed>(failed.result)
        assertEquals(
            "POST /submit HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "content-length: 4\r\n" +
                "\r\n" +
                "bo",
            originOutput.toString(Charsets.UTF_8),
        )
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
        assertEquals('H'.code, originInput.read())
    }

    @Test
    fun `returns response forwarding failure after response head and partial body are written`() {
        val accepted = accepted(httpProxyRequest(method = "GET", originTarget = "/resource"))
        val clientInput = ByteArrayInputStream(ByteArray(0))
        val originInputHeader = "HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\n"
        val originInput = ByteArrayInputStream((originInputHeader + "bo").toByteArray(Charsets.US_ASCII))
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
        )

        val failed = assertIs<HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed>(result)
        assertEquals(originInputHeader.toByteArray(Charsets.UTF_8).size, failed.responseHeaderBytesRead)
        assertIs<HttpProxyResponseStreamForwardingResult.BodyCopyFailed>(failed.result)
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 4\r\n" +
                "\r\n" +
                "bo",
            clientOutput.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `returns response forwarding rejection after response preflight without writing client bytes`() {
        val accepted = accepted(httpProxyRequest(method = "GET", originTarget = "/resource"))
        val clientInput = ByteArrayInputStream(ByteArray(0))
        val originInputHeader = "HTTP/1.1 200 OK\r\ntransfer-encoding: gzip\r\n\r\n"
        val originInput = ByteArrayInputStream((originInputHeader + "body").toByteArray(Charsets.US_ASCII))
        val originOutput = ByteArrayOutputStream()
        val clientOutput = ByteArrayOutputStream()

        val result = HttpProxyStreamExchangeForwarder.forward(
            accepted = accepted,
            clientInput = clientInput,
            originInput = originInput,
            originOutput = originOutput,
            clientOutput = clientOutput,
        )

        val failed = assertIs<HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed>(result)
        assertEquals(originInputHeader.toByteArray(Charsets.UTF_8).size, failed.responseHeaderBytesRead)
        assertEquals(
            HttpProxyResponseStreamForwardingResult.Rejected(
                HttpProxyResponseStreamForwardingRejectionReason.BodyFramingRejected(
                    HttpResponseBodyFramingRejectionReason.UnsupportedTransferEncoding,
                ),
            ),
            failed.result,
        )
        assertEquals(emptyList(), clientOutput.toByteArray().toList())
        assertEquals('b'.code, originInput.read())
    }

    private fun httpProxyRequest(
        method: String = "POST",
        originTarget: String = "/submit",
        headers: Map<String, List<String>> = emptyMap(),
    ): ParsedHttpRequest =
        ParsedHttpRequest(
            request = ParsedProxyRequest.HttpProxy(
                method = method,
                host = "origin.example",
                port = 80,
                originTarget = originTarget,
            ),
            headers = headers,
        )

    private fun accepted(request: ParsedHttpRequest): ProxyIngressStreamPreflightDecision.Accepted =
        ProxyIngressStreamPreflightDecision.Accepted(
            httpRequest = request,
            activeConnectionsAfterAdmission = 1,
            requiresAuditLog = false,
            headerBytesRead = 128,
        )
}
