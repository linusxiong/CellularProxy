package com.cellularproxy.app.service

import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.ManagementApiAuditRecord
import com.cellularproxy.cloudflare.CloudflareLocalManagementRequest
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiHandlerException
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class CloudflareManagementApiBridgeTest {
    @Test
    fun `public health without auth dispatches`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"healthy":true}"""),
            )
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response = bridge.handle(localRequest(method = HttpMethod.Get, originTarget = "/health"))

        assertEquals(listOf(ManagementApiOperation.Health), handler.operations)
        assertEquals(200, response.statusCode)
        assertEquals("""{"healthy":true}""", response.body)
        assertEquals(emptyList(), auditRecords)
    }

    @Test
    fun `api status without auth is rejected and handler is not invoked`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler, recordManagementAudit = {})

        val response = bridge.handle(localRequest(method = HttpMethod.Get, originTarget = "/api/status"))

        assertEquals(emptyList(), handler.operations)
        assertEquals(401, response.statusCode)
        assertEquals("Unauthorized\n", response.body)
        assertEquals("Bearer", response.headers["WWW-Authenticate"])
    }

    @Test
    fun `api status with correct bearer dispatches`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"status":"running"}"""),
            )
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Get,
                    originTarget = "/api/status",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(listOf(ManagementApiOperation.Status), handler.operations)
        assertEquals(200, response.statusCode)
        assertEquals("""{"status":"running"}""", response.body)
        assertEquals(emptyList(), auditRecords)
    }

    @Test
    fun `duplicate authorization values are rejected`() {
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler, recordManagementAudit = {})

        val response =
            bridge.handle(
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
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler, recordManagementAudit = {})

        val response =
            bridge.handle(
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
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(
                    statusCode = 202,
                    body = """{"accepted":true}""",
                    extraHeaders = linkedMapOf("X-Request-Id" to "request-123"),
                ),
            )
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler, recordManagementAudit = {})

        val response =
            bridge.handle(
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
        val bridge = CloudflareManagementApiBridge(admissionConfig, handler, recordManagementAudit = {})

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Get,
                    originTarget = "/api/status",
                    headers = mapOf("Authorization" to listOf("Bearer wrong-secret")),
                ),
            )

        val diagnosticText =
            listOf(
                response.toString(),
                response.toHttpString(),
                bridge.toString(),
            ).joinToString("\n")
        assertContains(diagnosticText, "Unauthorized")
        assertFalse(diagnosticText.contains("wrong-secret"))
        assertFalse(diagnosticText.contains("management-token"))
    }

    @Test
    fun `high impact cloudflare management response records audit entry`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
            )
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/cloudflare/start",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(202, response.statusCode)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.CloudflareStart,
                    outcome = ManagementApiAuditOutcome.Responded,
                    statusCode = 202,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `high impact cloudflare route rejection records audit entry`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/cloudflare/start?reason=remote",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(400, response.statusCode)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.CloudflareStart,
                    outcome = ManagementApiAuditOutcome.RouteRejected,
                    statusCode = 400,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `cloudflare service restart response callback runs after routed audit entry`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        var callbackObservedAuditRecords = emptyList<ManagementApiAuditRecord>()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(
                    statusCode = 202,
                    body = """{"accepted":true}""",
                    afterResponseSent = {
                        callbackObservedAuditRecords = auditRecords.toList()
                    },
                ),
            )
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/service/restart",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(202, response.statusCode)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceRestart,
                    outcome = ManagementApiAuditOutcome.Responded,
                    statusCode = 202,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                ),
            ),
            callbackObservedAuditRecords,
        )
    }

    @Test
    fun `cloudflare service restart route rejection records attempted operation`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204)),
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/service/restart?reason=remote",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(400, response.statusCode)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.ServiceRestart,
                    outcome = ManagementApiAuditOutcome.RouteRejected,
                    statusCode = 400,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `high impact cloudflare authorization rejection records audit entry`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit = auditRecords::add,
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/cloudflare/start",
                ),
            )

        assertEquals(401, response.statusCode)
        assertEquals(emptyList(), handler.operations)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = null,
                    outcome = ManagementApiAuditOutcome.AuthorizationRejected,
                    statusCode = 401,
                    disposition = null,
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `high impact cloudflare handler failure records audit entry before rethrowing`() {
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        val failure = IOException("cloudflare state unavailable")
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = ManagementApiHandler { throw failure },
                recordManagementAudit = auditRecords::add,
            )

        val thrown =
            assertFailsWith<ManagementApiHandlerException> {
                bridge.handle(
                    localRequest(
                        method = HttpMethod.Post,
                        originTarget = "/api/cloudflare/start",
                        headers = mapOf("Authorization" to listOf("Bearer management-token")),
                    ),
                )
            }

        assertEquals(ManagementApiOperation.CloudflareStart, thrown.operation)
        assertSame(failure, thrown.cause)
        assertEquals(
            listOf(
                ManagementApiAuditRecord(
                    operation = ManagementApiOperation.CloudflareStart,
                    outcome = ManagementApiAuditOutcome.HandlerFailed,
                    statusCode = null,
                    disposition = null,
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `management audit recorder failure does not replace command response`() {
        val failure = IOException("disk full")
        val reportedFailures = mutableListOf<Exception>()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
            )
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = handler,
                recordManagementAudit =
                    nonFatalManagementAuditRecorder(
                        recordManagementAudit = { throw failure },
                        reportManagementAuditFailure = reportedFailures::add,
                    ),
            )

        val response =
            bridge.handle(
                localRequest(
                    method = HttpMethod.Post,
                    originTarget = "/api/cloudflare/start",
                    headers = mapOf("Authorization" to listOf("Bearer management-token")),
                ),
            )

        assertEquals(202, response.statusCode)
        assertEquals(listOf(ManagementApiOperation.CloudflareStart), handler.operations)
        assertEquals(1, reportedFailures.size)
        assertSame(failure, reportedFailures.single())
    }

    @Test
    fun `management audit recorder failure does not replace handler failure`() {
        val auditFailure = IOException("disk full")
        val handlerFailure = IOException("cloudflare state unavailable")
        val reportedFailures = mutableListOf<Exception>()
        val bridge =
            CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = ManagementApiHandler { throw handlerFailure },
                recordManagementAudit =
                    nonFatalManagementAuditRecorder(
                        recordManagementAudit = { throw auditFailure },
                        reportManagementAuditFailure = reportedFailures::add,
                    ),
            )

        val thrown =
            assertFailsWith<ManagementApiHandlerException> {
                bridge.handle(
                    localRequest(
                        method = HttpMethod.Post,
                        originTarget = "/api/cloudflare/start",
                        headers = mapOf("Authorization" to listOf("Bearer management-token")),
                    ),
                )
            }

        assertSame(handlerFailure, thrown.cause)
        assertEquals(1, reportedFailures.size)
        assertSame(auditFailure, reportedFailures.single())
    }

    private val admissionConfig =
        ProxyRequestAdmissionConfig(
            proxyAuthentication =
                ProxyAuthenticationConfig(
                    authEnabled = false,
                    credential = ProxyCredential(username = "proxy-user", password = "proxy-password"),
                ),
            managementApiToken = "management-token",
        )

    private fun localRequest(
        method: HttpMethod,
        originTarget: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): CloudflareLocalManagementRequest = CloudflareLocalManagementRequest(
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
