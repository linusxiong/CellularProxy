package com.cellularproxy.proxy.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpResponseBodyFramingPolicyTest {
    @Test
    fun `classifies fixed-length response bodies`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("42")),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.FixedLength(42)),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }

    @Test
    fun `classifies statuses that cannot carry response bodies as no body`() {
        listOf(100, 101, 204, 304).forEach { statusCode ->
            val response =
                ParsedHttpResponse(
                    statusCode = statusCode,
                    reasonPhrase = "No Body",
                    headers = linkedMapOf("content-length" to listOf("123")),
                )

            assertEquals(
                HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.NoBody),
                HttpResponseBodyFramingPolicy.classify(response),
            )
        }
    }

    @Test
    fun `classifies HEAD responses as no body despite body framing headers`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("123")),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.NoBody),
            HttpResponseBodyFramingPolicy.classify(response, requestMethod = "HEAD"),
        )
    }

    @Test
    fun `classifies final chunked transfer encoding responses`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("transfer-encoding" to listOf("chunked")),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.Chunked),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }

    @Test
    fun `classifies responses without explicit framing as close delimited`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = emptyMap(),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.CloseDelimited),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }

    @Test
    fun `rejects ambiguous content length headers`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("2", "3")),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.AmbiguousContentLength),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }

    @Test
    fun `allows duplicate matching content length headers`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = linkedMapOf("content-length" to listOf("7", "7")),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.FixedLength(7)),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }

    @Test
    fun `rejects invalid content length syntax`() {
        listOf("-1", "+1", " 1", "1 ", "0x10", "١").forEach { contentLength ->
            val response =
                ParsedHttpResponse(
                    statusCode = 200,
                    reasonPhrase = "OK",
                    headers = linkedMapOf("content-length" to listOf(contentLength)),
                )

            assertEquals(
                HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.InvalidContentLength),
                HttpResponseBodyFramingPolicy.classify(response),
            )
        }
    }

    @Test
    fun `rejects unsupported transfer encodings`() {
        listOf(
            listOf("gzip"),
            listOf("gzip, chunked"),
            listOf("chunked", "gzip"),
        ).forEach { transferEncoding ->
            val response =
                ParsedHttpResponse(
                    statusCode = 200,
                    reasonPhrase = "OK",
                    headers = linkedMapOf("transfer-encoding" to transferEncoding),
                )

            assertEquals(
                HttpResponseBodyFramingResult.Rejected(HttpResponseBodyFramingRejectionReason.UnsupportedTransferEncoding),
                HttpResponseBodyFramingPolicy.classify(response),
            )
        }
    }

    @Test
    fun `transfer encoding takes precedence over content length`() {
        val response =
            ParsedHttpResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                headers =
                    linkedMapOf(
                        "transfer-encoding" to listOf("chunked"),
                        "content-length" to listOf("5"),
                    ),
            )

        assertEquals(
            HttpResponseBodyFramingResult.Accepted(HttpResponseBodyFraming.Chunked),
            HttpResponseBodyFramingPolicy.classify(response),
        )
    }
}
