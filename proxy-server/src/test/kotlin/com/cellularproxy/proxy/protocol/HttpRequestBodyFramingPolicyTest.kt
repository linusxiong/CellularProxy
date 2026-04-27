package com.cellularproxy.proxy.protocol

import com.cellularproxy.shared.management.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRequestBodyFramingPolicyTest {
    @Test
    fun `classifies http proxy requests without body framing as no body`() {
        val request = httpProxyRequest(headers = emptyMap())

        assertEquals(
            HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.NoBody),
            HttpRequestBodyFramingPolicy.classify(request),
        )
    }

    @Test
    fun `classifies fixed-length http proxy request bodies`() {
        val request = httpProxyRequest(headers = linkedMapOf("content-length" to listOf("42")))

        assertEquals(
            HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.FixedLength(42)),
            HttpRequestBodyFramingPolicy.classify(request),
        )
    }

    @Test
    fun `classifies zero-length http proxy request bodies as fixed length`() {
        val request = httpProxyRequest(headers = linkedMapOf("content-length" to listOf("0")))

        assertEquals(
            HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.FixedLength(0)),
            HttpRequestBodyFramingPolicy.classify(request),
        )
    }

    @Test
    fun `rejects ambiguous request content length headers`() {
        listOf(
            linkedMapOf("content-length" to listOf("7", "7")),
            linkedMapOf("content-length" to listOf("7", "8")),
        ).forEach { headers ->
            val request = httpProxyRequest(headers = headers)

            assertEquals(
                HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.AmbiguousContentLength),
                HttpRequestBodyFramingPolicy.classify(request),
            )
        }
    }

    @Test
    fun `rejects invalid request content length syntax`() {
        listOf("-1", "+1", " 1", "1 ", "0x10", "\u0661").forEach { contentLength ->
            val request = httpProxyRequest(headers = linkedMapOf("content-length" to listOf(contentLength)))

            assertEquals(
                HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.InvalidContentLength),
                HttpRequestBodyFramingPolicy.classify(request),
            )
        }
    }

    @Test
    fun `rejects unsupported transfer encoded request bodies`() {
        listOf(
            listOf("chunked"),
            listOf("gzip"),
            listOf("gzip, chunked"),
        ).forEach { transferEncoding ->
            val request = httpProxyRequest(headers = linkedMapOf("transfer-encoding" to transferEncoding))

            assertEquals(
                HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.UnsupportedTransferEncoding),
                HttpRequestBodyFramingPolicy.classify(request),
            )
        }
    }

    @Test
    fun `rejects transfer encoding before content length`() {
        val request =
            httpProxyRequest(
                headers =
                    linkedMapOf(
                        "transfer-encoding" to listOf("chunked"),
                        "content-length" to listOf("5"),
                    ),
            )

        assertEquals(
            HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.UnsupportedTransferEncoding),
            HttpRequestBodyFramingPolicy.classify(request),
        )
    }

    @Test
    fun `classifies connect and management requests without body framing as no body`() {
        listOf(
            ParsedHttpRequest(
                request = ParsedProxyRequest.ConnectTunnel("example.com", 443),
                headers = emptyMap(),
            ),
            ParsedHttpRequest(
                request =
                    ParsedProxyRequest.Management(
                        method = HttpMethod.Post,
                        originTarget = "/api/service/stop",
                        requiresToken = true,
                        requiresAuditLog = true,
                    ),
                headers = emptyMap(),
            ),
        ).forEach { request ->
            assertEquals(
                HttpRequestBodyFramingResult.Accepted(HttpRequestBodyFraming.NoBody),
                HttpRequestBodyFramingPolicy.classify(request),
            )
        }
    }

    @Test
    fun `rejects body framing on connect and management requests`() {
        listOf(
            ParsedHttpRequest(
                request = ParsedProxyRequest.ConnectTunnel("example.com", 443),
                headers = linkedMapOf("content-length" to listOf("1")),
            ),
            ParsedHttpRequest(
                request =
                    ParsedProxyRequest.Management(
                        method = HttpMethod.Post,
                        originTarget = "/api/service/stop",
                        requiresToken = true,
                        requiresAuditLog = true,
                    ),
                headers = linkedMapOf("content-length" to listOf("1")),
            ),
        ).forEach { request ->
            assertEquals(
                HttpRequestBodyFramingResult.Rejected(HttpRequestBodyFramingRejectionReason.BodyNotSupported),
                HttpRequestBodyFramingPolicy.classify(request),
            )
        }
    }

    private fun httpProxyRequest(headers: Map<String, List<String>>): ParsedHttpRequest =
        ParsedHttpRequest(
            request =
                ParsedProxyRequest.HttpProxy(
                    method = "POST",
                    host = "origin.example",
                    port = 80,
                    originTarget = "/submit",
                ),
            headers = headers,
        )
}
