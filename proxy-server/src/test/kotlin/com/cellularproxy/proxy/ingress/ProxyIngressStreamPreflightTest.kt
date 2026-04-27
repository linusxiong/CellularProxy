package com.cellularproxy.proxy.ingress

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProxyIngressStreamPreflightTest {
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
                    managementApiToken = "management-token",
                ),
        )

    @Test
    fun `accepts complete stream header block without consuming request body bytes`() {
        val headerBlock =
            "POST http://example.com/upload HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Proxy-Authorization: ${validProxyAuthorization()}\r\n" +
                "Content-Length: 4\r\n" +
                "\r\n"
        val input = ByteArrayInputStream((headerBlock + "body").toByteArray(Charsets.US_ASCII))

        val decision =
            ProxyIngressStreamPreflight.evaluate(
                config = config,
                activeConnections = 1,
                input = input,
            )

        val accepted = assertIs<ProxyIngressStreamPreflightDecision.Accepted>(decision)
        val request = assertIs<ParsedProxyRequest.HttpProxy>(accepted.request)
        assertEquals(request, accepted.httpRequest.request)
        assertEquals(listOf("4"), accepted.httpRequest.headers["content-length"])
        assertEquals(listOf(validProxyAuthorization()), accepted.httpRequest.headers["proxy-authorization"])
        assertEquals("example.com", request.host)
        assertEquals(headerBlock.length, accepted.headerBytesRead)
        assertEquals(2L, accepted.activeConnectionsAfterAdmission)
        assertFalse(accepted.requiresAuditLog)
        assertFalse(accepted.toString().contains(validProxyAuthorization()))
        assertFalse(accepted.toString().contains("proxy-authorization", ignoreCase = true))
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `rejects at capacity before reading from the stream`() {
        val input = ByteArrayInputStream("not a request".toByteArray(Charsets.US_ASCII))

        val decision =
            ProxyIngressStreamPreflight.evaluate(
                config = config,
                activeConnections = 2,
                input = input,
            )

        val rejected = assertIs<ProxyIngressStreamPreflightDecision.Rejected>(decision)
        assertIs<ProxyServerFailure.ConnectionLimit>(rejected.failure)
        assertEquals(0, rejected.headerBytesRead)
        assertEquals('n'.code, input.read())
    }

    @Test
    fun `allows management stream requests while paused even when proxy connection capacity is full`() {
        val headerBlock =
            "GET /api/status HTTP/1.1\r\n" +
                "Authorization: Bearer management-token\r\n" +
                "\r\n"
        val input = ByteArrayInputStream(headerBlock.toByteArray(Charsets.US_ASCII))

        val decision =
            ProxyIngressStreamPreflight.evaluate(
                config = config.copy(proxyRequestsPaused = true),
                activeConnections = 2,
                input = input,
            )

        val accepted = assertIs<ProxyIngressStreamPreflightDecision.Accepted>(decision)
        val request = assertIs<ParsedProxyRequest.Management>(accepted.request)
        assertEquals("/api/status", request.originTarget)
        assertEquals(headerBlock.length, accepted.headerBytesRead)
        assertEquals(3L, accepted.activeConnectionsAfterAdmission)
        assertFalse(accepted.requiresAuditLog)
    }

    @Test
    fun `maps incomplete stream header blocks to safe bad request responses`() {
        val input = ByteArrayInputStream("GET http://example.com/ HTTP/1.1\r\nHost: example.com".toByteArray(Charsets.US_ASCII))

        val decision =
            ProxyIngressStreamPreflight.evaluate(
                config = config,
                activeConnections = 0,
                input = input,
            )

        val rejected = assertIs<ProxyIngressStreamPreflightDecision.Rejected>(decision)
        val failure = assertIs<ProxyServerFailure.HeaderBlockParse>(rejected.failure)
        assertEquals(HttpRequestHeaderBlockRejectionReason.IncompleteHeaderBlock, failure.reason)
        assertEquals(400, assertIs<ProxyErrorResponseDecision.Emit>(rejected.response).response.statusCode)
        assertEquals(51, rejected.headerBytesRead)
    }

    @Test
    fun `maps malformed stream header encoding without exposing raw bytes`() {
        val invalidUtf8HeaderBlock =
            byteArrayOf(
                'G'.code.toByte(),
                'E'.code.toByte(),
                'T'.code.toByte(),
                ' '.code.toByte(),
                '/'.code.toByte(),
                ' '.code.toByte(),
                'H'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                'P'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                '.'.code.toByte(),
                '1'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                'X'.code.toByte(),
                ':'.code.toByte(),
                ' '.code.toByte(),
                0xC3.toByte(),
                0x28.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
        val input = ByteArrayInputStream(invalidUtf8HeaderBlock + "NEXT".toByteArray(Charsets.US_ASCII))

        val decision =
            ProxyIngressStreamPreflight.evaluate(
                config = config,
                activeConnections = 0,
                input = input,
            )

        val rejected = assertIs<ProxyIngressStreamPreflightDecision.Rejected>(decision)
        val failure = assertIs<ProxyServerFailure.HeaderBlockParse>(rejected.failure)
        assertEquals(HttpRequestHeaderBlockRejectionReason.MalformedHeaderEncoding, failure.reason)
        assertEquals(invalidUtf8HeaderBlock.size, rejected.headerBytesRead)
        assertEquals('N'.code, input.read())
    }

    private fun validProxyAuthorization(): String {
        val payload = credential.canonicalBasicPayload().toByteArray(Charsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(payload)}"
    }
}
