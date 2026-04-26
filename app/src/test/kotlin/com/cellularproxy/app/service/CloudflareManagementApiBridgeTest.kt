package com.cellularproxy.app.service

import com.cellularproxy.cloudflare.CloudflareLocalManagementRequest
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloudflareManagementApiBridgeTest {
    @Test
    fun `public health without auth dispatches`() {
        val handler = RecordingManagementApiHandler(
            ManagementApiResponse.json(statusCode = 200, body = """{"healthy":true}"""),
        )
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(localRequest(method = HttpMethod.Get, originTarget = "/health"))

        assertEquals(listOf(ManagementApiOperation.Health), handler.operations)
        assertEquals(200, response.statusCode)
        assertEquals("""{"healthy":true}""", response.body)
    }

    @Test
    fun `api status without auth is rejected and handler is not invoked`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(localRequest(method = HttpMethod.Get, originTarget = "/api/status"))

        assertEquals(emptyList(), handler.operations)
        assertEquals(401, response.statusCode)
        assertEquals("Unauthorized\n", response.body)
        assertEquals("Bearer", response.headers["WWW-Authenticate"])
    }

    @Test
    fun `api status with correct bearer dispatches`() {
        val handler = RecordingManagementApiHandler(
            ManagementApiResponse.json(statusCode = 200, body = """{"status":"running"}"""),
        )
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(
            localRequest(
                method = HttpMethod.Get,
                originTarget = "/api/status",
                headers = mapOf("Authorization" to listOf("Bearer management-token")),
            ),
        )

        assertEquals(listOf(ManagementApiOperation.Status), handler.operations)
        assertEquals(200, response.statusCode)
        assertEquals("""{"status":"running"}""", response.body)
    }

    @Test
    fun `duplicate authorization values are rejected`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(
            localRequest(
                method = HttpMethod.Get,
                originTarget = "/api/status",
                headers = mapOf("Authorization" to listOf("Bearer management-token", "Bearer management-token")),
            ),
        )

        assertEquals(emptyList(), handler.operations)
        assertEquals(401, response.statusCode)
        assertEquals("Unauthorized\n", response.body)
    }

    @Test
    fun `unknown api with valid bearer gets management router not found`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(
            localRequest(
                method = HttpMethod.Get,
                originTarget = "/api/unknown",
                headers = mapOf("Authorization" to listOf("Bearer management-token")),
            ),
        )

        assertEquals(emptyList(), handler.operations)
        assertEquals(404, response.statusCode)
        assertEquals("""{"error":"not_found"}""", response.body)
    }

    @Test
    fun `response headers body and reason are preserved in conversion`() {
        val handler = RecordingManagementApiHandler(
            ManagementApiResponse.json(
                statusCode = 202,
                body = """{"accepted":true}""",
                extraHeaders = linkedMapOf("X-Request-Id" to "request-123"),
            ),
        )
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(
            localRequest(
                method = HttpMethod.Get,
                originTarget = "/api/status",
                headers = mapOf("Authorization" to listOf("Bearer management-token")),
            ),
        )

        assertEquals(202, response.statusCode)
        assertEquals("Accepted", response.reasonPhrase)
        assertEquals("""{"accepted":true}""", response.body)
        assertEquals("request-123", response.headers["X-Request-Id"])
        assertEquals("""{"accepted":true}""".toByteArray(Charsets.UTF_8).size.toString(), response.headers["Content-Length"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `admission diagnostics do not leak bearer token`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler)

        val response = bridge.handle(
            localRequest(
                method = HttpMethod.Get,
                originTarget = "/api/status",
                headers = mapOf("Authorization" to listOf("Bearer wrong-secret")),
            ),
        )

        val diagnosticText = listOf(
            response.toString(),
            response.toHttpString(),
            bridge.toString(),
        ).joinToString("\n")
        assertContains(diagnosticText, "Unauthorized")
        assertFalse(diagnosticText.contains("wrong-secret"))
        assertFalse(diagnosticText.contains("management-token"))
    }

    private val admissionConfig = ProxyRequestAdmissionConfig(
        proxyAuthentication = ProxyAuthenticationConfig(
            authEnabled = false,
            credential = ProxyCredential(username = "proxy-user", password = "proxy-password"),
        ),
        managementApiToken = "management-token",
    )

    private fun localRequest(
        method: HttpMethod,
        originTarget: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): CloudflareLocalManagementRequest =
        CloudflareLocalManagementRequest(
            method = method,
            originTarget = originTarget,
            headers = headers,
        )

    private class RecordingManagementApiHandler(
        private val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            return response
        }
    }
}
