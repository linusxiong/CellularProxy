package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

fun interface ManagementApiHandler {
    fun handle(operation: ManagementApiOperation): ManagementApiResponse
}

class ManagementApiHandlerException(
    val operation: ManagementApiOperation,
    val requiresAuditLog: Boolean,
    cause: Exception,
) : RuntimeException("Management API handler failed for operation $operation", cause)

sealed interface ManagementApiDispatchDecision {
    data class Respond(
        val operation: ManagementApiOperation,
        val response: ManagementApiResponse,
        val requiresAuditLog: Boolean,
    ) : ManagementApiDispatchDecision

    data class Reject(
        val response: ManagementApiResponse,
        val requiresAuditLog: Boolean,
    ) : ManagementApiDispatchDecision
}

class ManagementApiResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    headers: Map<String, String>,
    val body: String,
) {
    val headers: Map<String, String>

    init {
        require(statusCode in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
        require(reasonPhrase.isNotBlank()) { "HTTP reason phrase must not be blank" }
        require(reasonPhrase.none { it.isUnsafeHttpHeaderCharacter() }) {
            "HTTP reason phrase must not contain control characters"
        }
        headers.forEach { (name, value) ->
            require(name.isHttpToken()) { "HTTP header names must be valid tokens" }
            require(value.none { it.isUnsafeHttpHeaderCharacter() }) {
                "HTTP header values must not contain control characters"
            }
        }
        val normalizedHeaderNames = headers.keys.map { it.lowercase(Locale.US) }
        require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
            "HTTP header names must not contain case-variant duplicates"
        }
        require(!headers.containsHeader(TRANSFER_ENCODING_HEADER)) {
            "Transfer-Encoding is not supported for management API responses"
        }
        headers.headerValue(CONTENT_LENGTH_HEADER)?.let { contentLength ->
            require(contentLength == body.toByteArray(Charsets.UTF_8).size.toString()) {
                "Content-Length must match the UTF-8 response body length"
            }
        }

        this.headers = Collections.unmodifiableMap(LinkedHashMap(headers))
    }

    fun toHttpString(): String = buildString {
        append("HTTP/1.1 ")
        append(statusCode)
        append(' ')
        append(reasonPhrase)
        append(CRLF)
        headers.forEach { (name, value) ->
            append(name)
            append(": ")
            append(value)
            append(CRLF)
        }
        append(CRLF)
        append(body)
    }

    fun toByteArray(): ByteArray = toHttpString().toByteArray(Charsets.UTF_8)

    companion object {
        fun json(
            statusCode: Int,
            body: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): ManagementApiResponse {
            val headers = linkedMapOf<String, String>()
            headers.putAll(extraHeaders)
            headers.setProtectedHeader("Content-Type", "application/json; charset=utf-8")
            headers.setProtectedHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
            headers.setProtectedHeader("Cache-Control", "no-store")
            headers.setProtectedHeader("Connection", "close")

            return ManagementApiResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhraseFor(statusCode),
                headers = headers,
                body = body,
            )
        }

        fun empty(
            statusCode: Int,
            extraHeaders: Map<String, String> = emptyMap(),
        ): ManagementApiResponse {
            val headers = linkedMapOf<String, String>()
            headers.putAll(extraHeaders)
            headers.setProtectedHeader("Content-Length", "0")
            headers.setProtectedHeader("Cache-Control", "no-store")
            headers.setProtectedHeader("Connection", "close")

            return ManagementApiResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhraseFor(statusCode),
                headers = headers,
                body = "",
            )
        }
    }
}

object ManagementApiDispatcher {
    fun dispatch(
        request: ParsedProxyRequest.Management,
        handler: ManagementApiHandler,
    ): ManagementApiDispatchDecision =
        when (val route = ManagementApiRouter.route(request)) {
            is ManagementApiRouteDecision.Accepted -> {
                val response = try {
                    handler.handle(route.operation)
                } catch (failure: Exception) {
                    throw ManagementApiHandlerException(
                        operation = route.operation,
                        requiresAuditLog = route.requiresAuditLog,
                        cause = failure,
                    )
                }

                ManagementApiDispatchDecision.Respond(
                    operation = route.operation,
                    response = response,
                    requiresAuditLog = route.requiresAuditLog,
                )
            }
            is ManagementApiRouteDecision.Rejected ->
                ManagementApiDispatchDecision.Reject(
                    response = route.reason.toResponse(),
                    requiresAuditLog = request.requiresAuditLog,
                )
        }

    private fun ManagementApiRouteRejectionReason.toResponse(): ManagementApiResponse =
        when (this) {
            ManagementApiRouteRejectionReason.UnknownEndpoint ->
                ManagementApiResponse.json(statusCode = 404, body = """{"error":"not_found"}""")
            is ManagementApiRouteRejectionReason.UnsupportedMethod ->
                ManagementApiResponse.json(
                    statusCode = 405,
                    body = """{"error":"method_not_allowed"}""",
                    extraHeaders = linkedMapOf("Allow" to allowedMethod.httpToken()),
                )
            ManagementApiRouteRejectionReason.QueryUnsupported ->
                ManagementApiResponse.json(statusCode = 400, body = """{"error":"query_unsupported"}""")
        }
}

private fun reasonPhraseFor(statusCode: Int): String =
    when (statusCode) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Status"
    }

private fun MutableMap<String, String>.setProtectedHeader(name: String, value: String) {
    keys
        .filter { it.equals(name, ignoreCase = true) }
        .toList()
        .forEach(::remove)
    this[name] = value
}

private fun com.cellularproxy.shared.management.HttpMethod.httpToken(): String =
    when (this) {
        com.cellularproxy.shared.management.HttpMethod.Get -> "GET"
        com.cellularproxy.shared.management.HttpMethod.Post -> "POST"
        com.cellularproxy.shared.management.HttpMethod.Connect -> "CONNECT"
    }

private fun String.isHttpToken(): Boolean =
    isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Map<String, String>.containsHeader(name: String): Boolean =
    headerValue(name) != null

private fun Map<String, String>.headerValue(name: String): String? {
    val normalizedName = name.lowercase(Locale.US)
    return entries.singleOrNull { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }?.value
}

private fun Char.isUnsafeHttpHeaderCharacter(): Boolean =
    code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private const val CRLF = "\r\n"
private const val CONTENT_LENGTH_HEADER = "Content-Length"
private const val TRANSFER_ENCODING_HEADER = "Transfer-Encoding"
private const val DELETE_CONTROL_CHAR = 0x7F
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_STATUS_CODE_RANGE = 100..599
private val HTTP_TOKEN_CHARS = (
    ('0'..'9') +
        ('A'..'Z') +
        ('a'..'z') +
        listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
).toSet()
