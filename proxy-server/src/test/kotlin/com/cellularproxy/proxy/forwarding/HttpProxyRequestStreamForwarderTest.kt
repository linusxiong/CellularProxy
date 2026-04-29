package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.HttpBodyStreamCopyResult
import com.cellularproxy.proxy.protocol.HttpRequestBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpProxyRequestStreamForwarderTest {
    @Test
    fun `forwards sanitized request head without consuming input when request has no body`() {
        val request =
            httpProxyRequest(
                method = "GET",
                originTarget = "/resource",
                headers =
                    linkedMapOf(
                        "host" to listOf("attacker.example"),
                        "proxy-authorization" to listOf("Basic secret"),
                        "accept" to listOf("application/json"),
                    ),
            )
        val input = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Forwarded(
                host = "origin.example",
                port = 80,
                headerBytesWritten = output.toByteArray().size,
                bodyBytesWritten = 0,
            ),
            result,
        )
        assertEquals(
            "GET /resource HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "accept: application/json\r\n" +
                "\r\n",
            output.toString(Charsets.UTF_8),
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `forwards from accepted stream preflight decision without reparsing request headers`() {
        val request =
            httpProxyRequest(
                method = "GET",
                originTarget = "/resource",
                headers = linkedMapOf("accept" to listOf("application/json")),
            )
        val accepted =
            ProxyIngressStreamPreflightDecision.Accepted(
                httpRequest = request,
                activeConnectionsAfterAdmission = 1,
                requiresAuditLog = false,
                headerBytesRead = 128,
            )
        val input = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted,
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Forwarded(
                host = "origin.example",
                port = 80,
                headerBytesWritten = output.toByteArray().size,
                bodyBytesWritten = 0,
            ),
            result,
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `forwards fixed-length request body after sanitized request head`() {
        val request =
            httpProxyRequest(
                method = "POST",
                originTarget = "/upload",
                headers =
                    linkedMapOf(
                        "content-length" to listOf("4"),
                        "content-type" to listOf("application/octet-stream"),
                    ),
            )
        val input = ByteArrayInputStream("bodyNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
                bufferSize = 2,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Forwarded(
                host = "origin.example",
                port = 80,
                headerBytesWritten =
                    (
                        "POST /upload HTTP/1.1\r\n" +
                            "host: origin.example\r\n" +
                            "content-length: 4\r\n" +
                            "content-type: application/octet-stream\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                bodyBytesWritten = 4,
            ),
            result,
        )
        assertEquals(
            (
                "POST /upload HTTP/1.1\r\n" +
                    "host: origin.example\r\n" +
                    "content-length: 4\r\n" +
                    "content-type: application/octet-stream\r\n" +
                    "\r\n" +
                    "body"
            ).toByteArray(Charsets.UTF_8).toList(),
            output.toByteArray().toList(),
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `rejects unsupported request body framing before writing outbound bytes`() {
        val request =
            httpProxyRequest(
                headers = linkedMapOf("transfer-encoding" to listOf("chunked")),
            )
        val input = ByteArrayInputStream("4\r\nbody\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Rejected(
                reason =
                    HttpProxyRequestStreamForwardingRejectionReason.BodyFramingRejected(
                        HttpRequestBodyFramingRejectionReason.UnsupportedTransferEncoding,
                    ),
            ),
            result,
        )
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals('4'.code, input.read())
    }

    @Test
    fun `rejects expect header before writing outbound bytes`() {
        val request =
            httpProxyRequest(
                headers =
                    linkedMapOf(
                        "expect" to listOf("100-continue"),
                        "content-length" to listOf("4"),
                    ),
            )
        val input = ByteArrayInputStream("bodyNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Rejected(
                reason = HttpProxyRequestStreamForwardingRejectionReason.UnsupportedExpectHeader,
            ),
            result,
        )
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `rejects non-http-proxy requests before writing outbound bytes`() {
        val request =
            ParsedHttpRequest(
                request =
                    ParsedProxyRequest.Management(
                        method = HttpMethod.Get,
                        originTarget = "/health",
                        requiresToken = false,
                        requiresAuditLog = false,
                    ),
                headers = emptyMap(),
            )
        val input = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyRequestStreamForwardingResult.Rejected(
                reason = HttpProxyRequestStreamForwardingRejectionReason.NotHttpProxyRequest,
            ),
            result,
        )
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `reports premature fixed-length body copy after writing forwarded head and partial body`() {
        val request = httpProxyRequest(headers = linkedMapOf("content-length" to listOf("4")))
        val input = ByteArrayInputStream("bo".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        val failed = assertIs<HttpProxyRequestStreamForwardingResult.BodyCopyFailed>(result)
        assertEquals("origin.example", failed.host)
        assertEquals(80, failed.port)
        assertEquals(output.toByteArray().size - 2, failed.headerBytesWritten)
        assertEquals(
            HttpBodyStreamCopyResult.PrematureEnd(
                bytesCopied = 2,
                expectedBytes = 4,
            ),
            failed.copyResult,
        )
        assertEquals("bo", output.toByteArray().toString(Charsets.UTF_8).takeLast(2))
    }

    @Test
    fun `forwards zero-length fixed request body without consuming input`() {
        val request = httpProxyRequest(headers = linkedMapOf("content-length" to listOf("0")))
        val input = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyRequestStreamForwarder.forward(
                accepted = accepted(request),
                input = input,
                output = output,
            )

        val forwarded = assertIs<HttpProxyRequestStreamForwardingResult.Forwarded>(result)
        assertEquals(0, forwarded.bodyBytesWritten)
        assertEquals('N'.code, input.read())
        assertEquals(
            "POST /submit HTTP/1.1\r\n" +
                "host: origin.example\r\n" +
                "content-length: 0\r\n" +
                "\r\n",
            output.toString(Charsets.UTF_8),
        )
    }

    private fun httpProxyRequest(
        method: String = "POST",
        originTarget: String = "/submit",
        headers: Map<String, List<String>> = emptyMap(),
    ): ParsedHttpRequest = ParsedHttpRequest(
        request =
            ParsedProxyRequest.HttpProxy(
                method = method,
                host = "origin.example",
                port = 80,
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
}
