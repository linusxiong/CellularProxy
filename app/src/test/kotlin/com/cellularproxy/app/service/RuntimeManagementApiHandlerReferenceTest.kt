package com.cellularproxy.app.service

import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RuntimeManagementApiHandlerReferenceTest {
    @Test
    fun `uninstalled reference returns management unavailable response`() {
        val reference = RuntimeManagementApiHandlerReference()

        val response = reference.handle(ManagementApiOperation.Status)

        assertEquals(503, response.statusCode)
        assertEquals("Service Unavailable", response.reasonPhrase)
        assertEquals("""{"error":"management_unavailable"}""", response.body)
        assertEquals(
            """{"error":"management_unavailable"}""".toByteArray(Charsets.UTF_8).size.toString(),
            response.headers["Content-Length"],
        )
    }

    @Test
    fun `installed handler receives management operations until registration is closed`() {
        val reference = RuntimeManagementApiHandlerReference()
        val handler =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"status":"running"}"""),
            )

        val registration = reference.install(handler)

        assertSame(handler.response, reference.handle(ManagementApiOperation.Status))
        assertEquals(listOf(ManagementApiOperation.Status), handler.operations)

        registration.close()

        assertEquals(503, reference.handle(ManagementApiOperation.Status).statusCode)
    }

    @Test
    fun `closing stale registration does not uninstall replacement handler`() {
        val reference = RuntimeManagementApiHandlerReference()
        val first = RecordingManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val second =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"status":"replacement"}"""),
            )
        val firstRegistration = reference.install(first)
        val secondRegistration = reference.install(second)

        firstRegistration.close()

        assertSame(second.response, reference.handle(ManagementApiOperation.Status))

        secondRegistration.close()

        assertEquals(503, reference.handle(ManagementApiOperation.Status).statusCode)
    }

    @Test
    fun `replacing and closing installed closeable handlers closes them once`() {
        val reference = RuntimeManagementApiHandlerReference()
        val first = CloseableManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val second = CloseableManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val firstRegistration = reference.install(first)
        val secondRegistration = reference.install(second)

        assertEquals(1, first.closeCount)

        firstRegistration.close()
        secondRegistration.close()
        secondRegistration.close()

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun `installing replacement keeps replacement active when previous cleanup fails`() {
        val reference = RuntimeManagementApiHandlerReference()
        val first = ThrowingCloseManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val second =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"status":"replacement"}"""),
            )
        reference.install(first)

        val secondRegistration = reference.install(second)

        assertEquals(1, first.closeCount)
        assertSame(second.response, reference.handle(ManagementApiOperation.Status))

        secondRegistration.close()

        assertEquals(503, reference.handle(ManagementApiOperation.Status).statusCode)
    }

    @Test
    fun `installing replacement keeps replacement active when previous cleanup throws non-exception throwable`() {
        val reference = RuntimeManagementApiHandlerReference()
        val first = ThrowingErrorCloseManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val second =
            RecordingManagementApiHandler(
                ManagementApiResponse.json(statusCode = 200, body = """{"status":"replacement"}"""),
            )
        reference.install(first)

        val secondRegistration = reference.install(second)

        assertEquals(1, first.closeCount)
        assertSame(second.response, reference.handle(ManagementApiOperation.Status))

        secondRegistration.close()

        assertEquals(503, reference.handle(ManagementApiOperation.Status).statusCode)
    }

    @Test
    fun `closing current registration propagates current handler close failure once`() {
        val reference = RuntimeManagementApiHandlerReference()
        val handler = ThrowingCloseManagementApiHandler(ManagementApiResponse.empty(statusCode = 204))
        val registration = reference.install(handler)

        val failure =
            assertFailsWith<IllegalStateException> {
                registration.close()
            }

        assertEquals("close failed", failure.message)
        assertEquals(1, handler.closeCount)
        registration.close()
        assertEquals(1, handler.closeCount)
        assertEquals(503, reference.handle(ManagementApiOperation.Status).statusCode)
    }

    @Test
    fun `reference diagnostics do not leak installed handler details`() {
        val reference = RuntimeManagementApiHandlerReference()
        reference.install(SecretBearingManagementApiHandler)

        val diagnosticText =
            listOf(
                reference.toString(),
                reference.handle(ManagementApiOperation.Status).toHttpString(),
            ).joinToString("\n")

        assertContains(diagnosticText, "RuntimeManagementApiHandlerReference")
        assertFalse(diagnosticText.contains("handler-secret"))
    }

    private open class RecordingManagementApiHandler(
        val response: ManagementApiResponse,
    ) : ManagementApiHandler {
        val operations = mutableListOf<ManagementApiOperation>()

        override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
            operations += operation
            return response
        }
    }

    private class CloseableManagementApiHandler(
        response: ManagementApiResponse,
    ) : RecordingManagementApiHandler(response),
        Closeable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }

    private class ThrowingCloseManagementApiHandler(
        response: ManagementApiResponse,
    ) : RecordingManagementApiHandler(response),
        Closeable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            throw IllegalStateException("close failed")
        }
    }

    private class ThrowingErrorCloseManagementApiHandler(
        response: ManagementApiResponse,
    ) : RecordingManagementApiHandler(response),
        Closeable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            throw AssertionError("close failed")
        }
    }

    private object SecretBearingManagementApiHandler : ManagementApiHandler {
        override fun handle(operation: ManagementApiOperation): ManagementApiResponse =
            ManagementApiResponse.json(
                statusCode = 200,
                body = """{"secret":"redacted"}""",
            )

        override fun toString(): String = "SecretBearingManagementApiHandler(handler-secret)"
    }
}
