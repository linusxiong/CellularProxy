package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.admission.ProxyRequestAdmissionDecision
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

data class ConnectTunnelEstablishmentPlan(
    val host: String,
    val port: Int,
    val response: ConnectTunnelEstablishedResponse,
) {
    init {
        require(host.isNotBlank()) { "Connect tunnel host must not be blank" }
        require(port in VALID_CONNECT_PORT_RANGE) { "Connect tunnel port must be in range 1..65535" }
    }
}

class ConnectTunnelEstablishedResponse(
    val reasonPhrase: String = DEFAULT_CONNECT_REASON_PHRASE,
    headers: Map<String, String> = emptyMap(),
) {
    val headers: Map<String, String>

    init {
        require(reasonPhrase.isNotBlank()) { "Reason phrase must not be blank" }
        require(reasonPhrase.none(Char::isUnsafeHttpHeaderCharacter)) {
            "Reason phrase must not contain control characters"
        }

        val copiedHeaders = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            require(name.isHttpToken()) { "Header name must be a valid HTTP token" }
            require(value.none(Char::isUnsafeHttpHeaderCharacter)) {
                "Header value must not contain control characters"
            }
            copiedHeaders[name] = value
        }

        val normalizedHeaderNames = copiedHeaders.keys.map { it.lowercase(Locale.US) }
        require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
            "HTTP header names must not contain case-variant duplicates"
        }
        require(CONTENT_LENGTH_HEADER !in normalizedHeaderNames) {
            "Content-Length is not valid for a successful CONNECT response"
        }
        require(TRANSFER_ENCODING_HEADER !in normalizedHeaderNames) {
            "Transfer-Encoding is not valid for a successful CONNECT response"
        }

        this.headers = Collections.unmodifiableMap(copiedHeaders)
    }

    fun toHttpString(): String =
        buildString {
            append("HTTP/1.1 200 ")
            append(reasonPhrase)
            append(CRLF)
            headers.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append(CRLF)
            }
            append(CRLF)
        }

    fun toByteArray(): ByteArray = toHttpString().toByteArray(Charsets.UTF_8)
}

object ConnectTunnelEstablishmentRenderer {
    fun render(admittedRequest: ProxyRequestAdmissionDecision.Accepted): ConnectTunnelEstablishmentPlan {
        val connectRequest =
            admittedRequest.request as? ParsedProxyRequest.ConnectTunnel
                ?: throw IllegalArgumentException("Only CONNECT requests can establish a tunnel")

        return ConnectTunnelEstablishmentPlan(
            host = connectRequest.host,
            port = connectRequest.port,
            response = ConnectTunnelEstablishedResponse(),
        )
    }
}

private fun String.isHttpToken(): Boolean = isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Char.isUnsafeHttpHeaderCharacter(): Boolean = code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private const val CRLF = "\r\n"
private const val DEFAULT_CONNECT_REASON_PHRASE = "Connection Established"
private const val CONTENT_LENGTH_HEADER = "content-length"
private const val TRANSFER_ENCODING_HEADER = "transfer-encoding"
private const val DELETE_CONTROL_CHAR = 0x7F
private val VALID_CONNECT_PORT_RANGE = 1..65535
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_TOKEN_CHARS =
    (
        ('0'..'9') +
            ('A'..'Z') +
            ('a'..'z') +
            listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
    ).toSet()
