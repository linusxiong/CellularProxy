package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.HttpBodyStreamCopyResult
import com.cellularproxy.proxy.protocol.HttpChunkedBodyMalformedReason
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingRejectionReason
import com.cellularproxy.proxy.protocol.ParsedHttpResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpProxyResponseStreamForwarderTest {
    @Test
    fun `forwards sanitized fixed-length origin response head and body`() {
        val response =
            parsedResponse(
                headers =
                    linkedMapOf(
                        "connection" to listOf("X-Hop"),
                        "x-hop" to listOf("remove-me"),
                        "content-length" to listOf("4"),
                        "content-type" to listOf("text/plain"),
                    ),
            )
        val input = ByteArrayInputStream("bodyNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
                bufferSize = 2,
            )

        assertEquals(
            HttpProxyResponseStreamForwardingResult.Forwarded(
                statusCode = 200,
                headerBytesWritten =
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "content-length: 4\r\n" +
                            "content-type: text/plain\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                bodyBytesWritten = 4,
                mustCloseClientConnection = false,
            ),
            result,
        )
        assertEquals(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "content-length: 4\r\n" +
                    "content-type: text/plain\r\n" +
                    "\r\n" +
                    "body"
            ).toByteArray(Charsets.UTF_8).toList(),
            output.toByteArray().toList(),
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `forwards HEAD response head without consuming response body bytes`() {
        val response = parsedResponse(headers = linkedMapOf("content-length" to listOf("123")))
        val input = ByteArrayInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "HEAD",
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyResponseStreamForwardingResult.Forwarded(
                statusCode = 200,
                headerBytesWritten = output.toByteArray().size,
                bodyBytesWritten = 0,
                mustCloseClientConnection = false,
            ),
            result,
        )
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-length: 123\r\n" +
                "\r\n",
            output.toString(Charsets.UTF_8),
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `forwards validated chunked origin response body`() {
        val response = parsedResponse(headers = linkedMapOf("transfer-encoding" to listOf("chunked")))
        val input = ByteArrayInputStream("4\r\nWiki\r\n0\r\n\r\nNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        val expectedBody = "4\r\nWiki\r\n0\r\n\r\n"
        assertEquals(
            HttpProxyResponseStreamForwardingResult.Forwarded(
                statusCode = 200,
                headerBytesWritten =
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "transfer-encoding: chunked\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                bodyBytesWritten = expectedBody.toByteArray(Charsets.US_ASCII).size.toLong(),
                mustCloseClientConnection = false,
            ),
            result,
        )
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "transfer-encoding: chunked\r\n" +
                "\r\n" +
                expectedBody,
            output.toString(Charsets.UTF_8),
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `forwards close-delimited origin response until end of stream`() {
        val response = parsedResponse(headers = linkedMapOf("content-type" to listOf("text/plain")))
        val input = ByteArrayInputStream("payload".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
                bufferSize = 3,
            )

        assertEquals(
            HttpProxyResponseStreamForwardingResult.Forwarded(
                statusCode = 200,
                headerBytesWritten =
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "content-type: text/plain\r\n" +
                            "\r\n"
                    ).toByteArray(Charsets.UTF_8).size,
                bodyBytesWritten = 7,
                mustCloseClientConnection = true,
            ),
            result,
        )
        assertEquals(
            "HTTP/1.1 200 OK\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n" +
                "payload",
            output.toString(Charsets.UTF_8),
        )
        assertEquals(-1, input.read())
    }

    @Test
    fun `reports premature fixed-length body copy after writing forwarded head and partial body`() {
        val response = parsedResponse(headers = linkedMapOf("content-length" to listOf("4")))
        val input = ByteArrayInputStream("bo".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        val failed = assertIs<HttpProxyResponseStreamForwardingResult.BodyCopyFailed>(result)
        assertEquals(200, failed.statusCode)
        assertEquals(output.toByteArray().size - 2, failed.headerBytesWritten)
        assertEquals(false, failed.mustCloseClientConnection)
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
    fun `rejects unsupported response body framing before writing outbound bytes`() {
        val response = parsedResponse(headers = linkedMapOf("transfer-encoding" to listOf("gzip")))
        val input = ByteArrayInputStream("body".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyResponseStreamForwardingResult.Rejected(
                reason =
                    HttpProxyResponseStreamForwardingRejectionReason.BodyFramingRejected(
                        HttpResponseBodyFramingRejectionReason.UnsupportedTransferEncoding,
                    ),
            ),
            result,
        )
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `rejects unsafe response heads before writing outbound bytes`() {
        val response =
            parsedResponse(
                headers =
                    linkedMapOf(
                        "connection" to listOf("Transfer-Encoding"),
                        "transfer-encoding" to listOf("chunked"),
                    ),
            )
        val input = ByteArrayInputStream("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        assertEquals(
            HttpProxyResponseStreamForwardingResult.Rejected(
                reason = HttpProxyResponseStreamForwardingRejectionReason.ResponseHeadRejected,
            ),
            result,
        )
        assertEquals(emptyList(), output.toByteArray().toList())
        assertEquals('0'.code, input.read())
    }

    @Test
    fun `reports response body copy failure after writing forwarded head and partial body`() {
        val response = parsedResponse(headers = linkedMapOf("transfer-encoding" to listOf("chunked")))
        val input = ByteArrayInputStream("4\r\nWi".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        val failed = assertIs<HttpProxyResponseStreamForwardingResult.BodyCopyFailed>(result)
        assertEquals(200, failed.statusCode)
        assertEquals(output.toByteArray().size - "4\r\nWi".toByteArray(Charsets.US_ASCII).size, failed.headerBytesWritten)
        assertEquals(
            HttpBodyStreamCopyResult.ChunkedPrematureEnd(
                bytesCopied = "4\r\nWi".toByteArray(Charsets.US_ASCII).size.toLong(),
            ),
            failed.copyResult,
        )
    }

    @Test
    fun `surfaces malformed chunk failures without reporting them as forwarded`() {
        val response = parsedResponse(headers = linkedMapOf("transfer-encoding" to listOf("chunked")))
        val input = ByteArrayInputStream("z\r\n".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result =
            HttpProxyResponseStreamForwarder.forward(
                response = response,
                requestMethod = "GET",
                input = input,
                output = output,
            )

        val failed = assertIs<HttpProxyResponseStreamForwardingResult.BodyCopyFailed>(result)
        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 0,
                reason = HttpChunkedBodyMalformedReason.InvalidChunkSize,
            ),
            failed.copyResult,
        )
    }

    private fun parsedResponse(
        statusCode: Int = 200,
        reasonPhrase: String = "OK",
        headers: Map<String, List<String>> = emptyMap(),
    ): ParsedHttpResponse = ParsedHttpResponse(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = headers,
    )
}
