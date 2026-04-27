package com.cellularproxy.proxy.errors

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionRejectionReason
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionRejectionReason
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ProxyRequestLineRejectionReason
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

sealed interface ProxyServerFailure {
    data class HeaderBlockParse(
        val reason: HttpRequestHeaderBlockRejectionReason,
        val requestLineRejectionReason: ProxyRequestLineRejectionReason? = null,
    ) : ProxyServerFailure {
        init {
            require((reason == HttpRequestHeaderBlockRejectionReason.RequestLineRejected) == (requestLineRejectionReason != null)) {
                "Request-line rejection details are required only for request-line rejections"
            }
        }
    }

    data class Admission(
        val reason: ProxyRequestAdmissionRejectionReason,
    ) : ProxyServerFailure

    data class ConnectionLimit(
        val reason: ConnectionLimitAdmissionRejectionReason,
    ) : ProxyServerFailure

    data object SelectedRouteUnavailable : ProxyServerFailure

    data object ProxyRequestsPaused : ProxyServerFailure

    data object DnsResolutionFailed : ProxyServerFailure

    data object OutboundConnectionFailed : ProxyServerFailure

    data object OutboundConnectionTimeout : ProxyServerFailure

    data object IdleTimeout : ProxyServerFailure

    data object ClientDisconnected : ProxyServerFailure
}

sealed interface ProxyErrorResponseDecision {
    data class Emit(
        val response: ProxyErrorResponse,
    ) : ProxyErrorResponseDecision

    data object Suppress : ProxyErrorResponseDecision
}

class ProxyErrorResponse(
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
            "Transfer-Encoding is not supported for proxy error responses"
        }
        headers.headerValue(CONTENT_LENGTH_HEADER)?.let { contentLength ->
            require(contentLength == body.toByteArray(Charsets.UTF_8).size.toString()) {
                "Content-Length must match the UTF-8 response body length"
            }
        }

        this.headers = Collections.unmodifiableMap(LinkedHashMap(headers))
    }

    fun toHttpString(): String =
        buildString {
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
}

object ProxyErrorResponseMapper {
    fun map(failure: ProxyServerFailure): ProxyErrorResponseDecision =
        when (failure) {
            is ProxyServerFailure.HeaderBlockParse ->
                emit(statusCode = 400, reasonPhrase = "Bad Request", body = "Bad request\n")
            is ProxyServerFailure.Admission ->
                mapAdmission(failure.reason)
            is ProxyServerFailure.ConnectionLimit ->
                emit(statusCode = 503, reasonPhrase = "Service Unavailable", body = "Service unavailable\n")
            ProxyServerFailure.SelectedRouteUnavailable ->
                emit(statusCode = 503, reasonPhrase = "Service Unavailable", body = "Service unavailable\n")
            ProxyServerFailure.ProxyRequestsPaused ->
                emit(statusCode = 503, reasonPhrase = "Service Unavailable", body = "Service unavailable\n")
            ProxyServerFailure.DnsResolutionFailed ->
                emit(statusCode = 502, reasonPhrase = "Bad Gateway", body = "Bad gateway\n")
            ProxyServerFailure.OutboundConnectionFailed ->
                emit(statusCode = 502, reasonPhrase = "Bad Gateway", body = "Bad gateway\n")
            ProxyServerFailure.OutboundConnectionTimeout ->
                emit(statusCode = 504, reasonPhrase = "Gateway Timeout", body = "Gateway timeout\n")
            ProxyServerFailure.IdleTimeout ->
                emit(statusCode = 408, reasonPhrase = "Request Timeout", body = "Request timeout\n")
            ProxyServerFailure.ClientDisconnected ->
                ProxyErrorResponseDecision.Suppress
        }

    private fun mapAdmission(reason: ProxyRequestAdmissionRejectionReason): ProxyErrorResponseDecision =
        when (reason) {
            ProxyRequestAdmissionRejectionReason.DuplicateProxyAuthorizationHeader,
            is ProxyRequestAdmissionRejectionReason.ProxyAuthentication,
            ->
                emit(
                    statusCode = 407,
                    reasonPhrase = "Proxy Authentication Required",
                    body = "Proxy authentication required\n",
                    extraHeaders = linkedMapOf("Proxy-Authenticate" to "Basic realm=\"CellularProxy\""),
                )
            is ProxyRequestAdmissionRejectionReason.ManagementAuthorization ->
                emit(
                    statusCode = 401,
                    reasonPhrase = "Unauthorized",
                    body = "Unauthorized\n",
                    extraHeaders = linkedMapOf("WWW-Authenticate" to "Bearer"),
                )
        }

    private fun emit(
        statusCode: Int,
        reasonPhrase: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ProxyErrorResponseDecision.Emit {
        val headers = linkedMapOf<String, String>()
        headers.putAll(extraHeaders)
        headers["Content-Type"] = "text/plain; charset=utf-8"
        headers["Content-Length"] = body.toByteArray(Charsets.UTF_8).size.toString()
        headers["Connection"] = "close"

        return ProxyErrorResponseDecision.Emit(
            ProxyErrorResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                headers = headers,
                body = body,
            ),
        )
    }
}

private fun String.isHttpToken(): Boolean = isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Map<String, String>.containsHeader(name: String): Boolean = headerValue(name) != null

private fun Map<String, String>.headerValue(name: String): String? {
    val normalizedName = name.lowercase(Locale.US)
    return entries.singleOrNull { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }?.value
}

private fun Char.isUnsafeHttpHeaderCharacter(): Boolean = code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private const val CRLF = "\r\n"
private const val CONTENT_LENGTH_HEADER = "Content-Length"
private const val TRANSFER_ENCODING_HEADER = "Transfer-Encoding"
private const val DELETE_CONTROL_CHAR = 0x7F
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_STATUS_CODE_RANGE = 100..599
private val HTTP_TOKEN_CHARS =
    (
        ('0'..'9') +
            ('A'..'Z') +
            ('a'..'z') +
            listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
    ).toSet()
