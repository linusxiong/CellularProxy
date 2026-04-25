package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.util.Collections
import java.util.Locale

class ForwardedHttpRequest(
    val host: String,
    val port: Int,
    val requestLine: String,
    headers: Map<String, List<String>>,
) {
    val headers: Map<String, List<String>>

    init {
        require(host.isNotBlank()) { "Forward host must not be blank" }
        require(port in VALID_PORT_RANGE) { "Forward port must be in range 1..65535" }
        require(requestLine.isSafeSingleLine()) { "Request line must not contain control characters" }

        val copiedHeaders = linkedMapOf<String, List<String>>()
        headers.forEach { (name, values) ->
            require(name.isHttpToken()) { "Header name must be a valid HTTP token" }
            require(values.isNotEmpty()) { "Header values must not be empty" }
            copiedHeaders[name] = Collections.unmodifiableList(
                values.map { value ->
                    require(value.none(Char::isDisallowedHeaderValueControl)) {
                        "Header value must not contain control characters"
                    }
                    value
                },
            )
        }
        this.headers = Collections.unmodifiableMap(copiedHeaders)
    }

    fun toHttpString(): String =
        buildString {
            append(requestLine).append(CRLF)
            headers.forEach { (name, values) ->
                values.forEach { value ->
                    append(name).append(": ").append(value).append(CRLF)
                }
            }
            append(CRLF)
        }

    fun toByteArray(): ByteArray = toHttpString().toByteArray(Charsets.UTF_8)
}

object HttpProxyForwardRequestRenderer {
    fun render(request: ParsedHttpRequest): ForwardedHttpRequest {
        val proxyRequest = request.request as? ParsedProxyRequest.HttpProxy
            ?: throw IllegalArgumentException("Only plain HTTP proxy requests can be rendered for HTTP forwarding")

        return ForwardedHttpRequest(
            host = proxyRequest.host,
            port = proxyRequest.port,
            requestLine = "${proxyRequest.method} ${proxyRequest.originTarget} HTTP/1.1",
            headers = linkedMapOf<String, List<String>>("host" to listOf(proxyRequest.forwardHostHeaderValue())) +
                request.headers.filterKeys { headerName ->
                    headerName.lowercase(Locale.US) !in HEADERS_REPLACED_BY_FORWARDER
                },
        )
    }

    private fun ParsedProxyRequest.HttpProxy.forwardHostHeaderValue(): String =
        if (port == HTTP_DEFAULT_PORT) host else "$host:$port"
}

private fun String.isSafeSingleLine(): Boolean =
    isNotEmpty() && none(Char::isISOControl)

private fun String.isHttpToken(): Boolean =
    isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Char.isDisallowedHeaderValueControl(): Boolean =
    code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private const val CRLF = "\r\n"
private const val DELETE_CONTROL_CHAR = 0x7F
private const val HTTP_DEFAULT_PORT = 80
private val VALID_PORT_RANGE = 1..65535
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HEADERS_REPLACED_BY_FORWARDER = setOf("host", "proxy-authorization", "proxy-connection")
private val HTTP_TOKEN_CHARS = (
    ('0'..'9') +
        ('A'..'Z') +
        ('a'..'z') +
        listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
).toSet()
