package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagementApiStreamExchangeHandlerTest {
    @Test
    fun `writes dispatched management response and preserves audit metadata`() {
        val output = ByteArrayOutputStream()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 202, body = """{"accepted":true}"""),
            )

        val result =
            ManagementApiStreamExchangeHandler(handler).handle(
                accepted =
                    accepted(
                        ParsedProxyRequest.Management(
                            method = HttpMethod.Post,
                            originTarget = "/api/service/stop",
                            requiresToken = true,
                            requiresAuditLog = true,
                        ),
                    ),
                clientOutput = output,
            )

        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(result)
        assertEquals(202, responded.statusCode)
        assertEquals(output.size(), responded.responseBytesWritten)
        assertTrue(responded.requiresAuditLog)
        assertEquals(ManagementApiStreamExchangeDisposition.Routed, responded.disposition)
        assertEquals(listOf(ManagementApiOperation.ServiceStop), handler.operations)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 202 Accepted")
        assertContains(output.toString(Charsets.UTF_8), """{"accepted":true}""")
    }

    @Test
    fun `runs response sent callback only after writing management response`() {
        val output = ByteArrayOutputStream()
        var callbackObservedOutput = ""
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(
                    statusCode = 202,
                    body = """{"accepted":true}""",
                    afterResponseSent = {
                        callbackObservedOutput = output.toString(Charsets.UTF_8)
                    },
                ),
            )

        ManagementApiStreamExchangeHandler(handler).handle(
            accepted =
                accepted(
                    ParsedProxyRequest.Management(
                        method = HttpMethod.Post,
                        originTarget = "/api/service/restart",
                        requiresToken = true,
                        requiresAuditLog = true,
                    ),
                ),
            clientOutput = output,
        )

        assertContains(callbackObservedOutput, "HTTP/1.1 202 Accepted")
        assertContains(callbackObservedOutput, """{"accepted":true}""")
    }

    @Test
    fun `writes management router rejection response and preserves precomputed audit metadata`() {
        val output = ByteArrayOutputStream()
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))

        val result =
            ManagementApiStreamExchangeHandler(handler).handle(
                accepted =
                    accepted(
                        ParsedProxyRequest.Management(
                            method = HttpMethod.Post,
                            originTarget = "/api/service/stop?reason=remote",
                            requiresToken = true,
                            requiresAuditLog = true,
                        ),
                    ),
                clientOutput = output,
            )

        val responded = assertIs<ManagementApiStreamExchangeHandlingResult.Responded>(result)
        assertEquals(400, responded.statusCode)
        assertEquals(output.size(), responded.responseBytesWritten)
        assertTrue(responded.requiresAuditLog)
        assertEquals(ManagementApiStreamExchangeDisposition.RouteRejected, responded.disposition)
        assertEquals(emptyList(), handler.operations)
        assertContains(output.toString(Charsets.UTF_8), "HTTP/1.1 400 Bad Request")
        assertContains(output.toString(Charsets.UTF_8), """{"error":"query_unsupported"}""")
        assertEquals(false, output.toString(Charsets.UTF_8).contains("reason=remote"))
    }

    @Test
    fun `service restart route rejection preserves attempted high impact operation in audit`() {
        val auditEvents = mutableListOf<ManagementApiStreamAuditEvent>()

        ManagementApiStreamExchangeHandler(
            handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204)),
            recordManagementAudit = auditEvents::add,
        ).handle(
            accepted =
                accepted(
                    ParsedProxyRequest.Management(
                        method = HttpMethod.Post,
                        originTarget = "/api/service/restart?reason=remote",
                        requiresToken = true,
                        requiresAuditLog = true,
                    ),
                ),
            clientOutput = ByteArrayOutputStream(),
        )

        assertEquals(
            listOf(
                ManagementApiStreamAuditEvent(
                    operation = ManagementApiOperation.ServiceRestart,
                    outcome = ManagementApiStreamAuditOutcome.RouteRejected,
                    statusCode = 400,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                ),
            ),
            auditEvents,
        )
    }

    @Test
    fun `returns unsupported for non-management accepted requests without writing or invoking handler`() {
        val output = ByteArrayOutputStream()
        val handler = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))

        val result =
            ManagementApiStreamExchangeHandler(handler).handle(
                accepted =
                    accepted(
                        ParsedProxyRequest.HttpProxy(
                            method = "GET",
                            host = "origin.example",
                            port = 80,
                            originTarget = "/",
                        ),
                    ),
                clientOutput = output,
            )

        assertEquals(
            ManagementApiStreamExchangeHandlingResult.UnsupportedAcceptedRequest(
                ManagementApiStreamExchangeUnsupportedReason.NotManagementRequest,
            ),
            result,
        )
        assertEquals(emptyList(), handler.operations)
        assertEquals(emptyList(), output.toByteArray().toList())
    }

    @Test
    fun `propagates dispatcher handler exception metadata before writing response bytes`() {
        val output = ByteArrayOutputStream()
        val failure = IOException("state backend unavailable")

        val thrown =
            assertFailsWith<ManagementApiHandlerException> {
                ManagementApiStreamExchangeHandler(ManagementApiHandler { throw failure }).handle(
                    accepted =
                        accepted(
                            ParsedProxyRequest.Management(
                                method = HttpMethod.Post,
                                originTarget = "/api/rotate/mobile-data",
                                requiresToken = true,
                                requiresAuditLog = true,
                            ),
                        ),
                    clientOutput = output,
                )
            }

        assertEquals(ManagementApiOperation.RotateMobileData, thrown.operation)
        assertTrue(thrown.requiresAuditLog)
        assertSame(failure, thrown.cause)
        assertEquals(emptyList(), output.toByteArray().toList())
    }

    private class RecordingManagementApiHandler(
        private val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            return response
        }
    }

    private fun accepted(request: ParsedProxyRequest): ProxyIngressStreamPreflightDecision.Accepted = ProxyIngressStreamPreflightDecision.Accepted(
        httpRequest =
            ParsedHttpRequest(
                request = request,
                headers = emptyMap(),
            ),
        activeConnectionsAfterAdmission = 1,
        requiresAuditLog = request is ParsedProxyRequest.Management && request.requiresAuditLog,
        headerBytesRead = 32,
    )
}
