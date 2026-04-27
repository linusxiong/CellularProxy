package com.cellularproxy.proxy.forwarding

import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale

class ForwardedHttpRequestHead(
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
        this.headers = copySafeForwardedRequestHeaders(headers)
    }

    fun toHttpString(): String = renderRequestHeaderString(requestLine, headers)

    fun toByteArray(): ByteArray = renderRequestHeaderString(requestLine, headers).toByteArray(Charsets.UTF_8)
}

class ForwardedHttpRequest(
    val host: String,
    val port: Int,
    val requestLine: String,
    headers: Map<String, List<String>>,
    body: ByteArray = EMPTY_BODY,
) {
    val headers: Map<String, List<String>>
    private val bodyBytes: ByteArray
    val body: ByteArray
        get() = bodyBytes.copyOf()

    init {
        require(host.isNotBlank()) { "Forward host must not be blank" }
        require(port in VALID_PORT_RANGE) { "Forward port must be in range 1..65535" }
        require(requestLine.isSafeSingleLine()) { "Request line must not contain control characters" }

        val copiedHeaders = copySafeForwardedRequestHeaders(headers)
        val contentLengthValues = copiedHeaders.caseInsensitiveHeaderValues(CONTENT_LENGTH_HEADER)
        val bodyCopy = body.copyOf()
        val contentLength = contentLengthValues.singleOrNull()
        if (contentLength == null) {
            require(bodyCopy.isEmpty()) {
                "Forwarded request bodies require a fixed Content-Length header"
            }
        } else {
            val declaredLength = contentLength.toLong()
            require(declaredLength == bodyCopy.size.toLong()) {
                "Content-Length must match the forwarded request body size"
            }
        }
        this.headers = copiedHeaders
        this.bodyBytes = bodyCopy
    }

    fun toHttpString(): String = headerString() + bodyBytes.decodeStrictUtf8()

    fun toByteArray(): ByteArray = headerString().toByteArray(Charsets.UTF_8) + bodyBytes

    private fun headerString(): String = renderRequestHeaderString(requestLine, headers)
}

object HttpProxyForwardRequestRenderer {
    fun renderHead(request: ParsedHttpRequest): ForwardedHttpRequestHead {
        val proxyRequest =
            request.request as? ParsedProxyRequest.HttpProxy
                ?: throw IllegalArgumentException("Only plain HTTP proxy requests can be rendered for HTTP forwarding")
        require(!request.headers.containsHeader(TRANSFER_ENCODING_HEADER)) {
            "Transfer-Encoding request bodies are not supported by the HTTP forward renderer"
        }

        return ForwardedHttpRequestHead(
            host = proxyRequest.host,
            port = proxyRequest.port,
            requestLine = "${proxyRequest.method} ${proxyRequest.originTarget} HTTP/1.1",
            headers = request.forwardedHeaders(proxyRequest),
        )
    }

    fun render(
        request: ParsedHttpRequest,
        body: ByteArray = EMPTY_BODY,
    ): ForwardedHttpRequest {
        val proxyRequest =
            request.request as? ParsedProxyRequest.HttpProxy
                ?: throw IllegalArgumentException("Only plain HTTP proxy requests can be rendered for HTTP forwarding")
        require(!request.headers.containsHeader(TRANSFER_ENCODING_HEADER)) {
            "Transfer-Encoding request bodies are not supported by the HTTP forward renderer"
        }

        return ForwardedHttpRequest(
            host = proxyRequest.host,
            port = proxyRequest.port,
            requestLine = "${proxyRequest.method} ${proxyRequest.originTarget} HTTP/1.1",
            headers = request.forwardedHeaders(proxyRequest),
            body = body,
        )
    }

    private fun ParsedHttpRequest.forwardedHeaders(proxyRequest: ParsedProxyRequest.HttpProxy): Map<String, List<String>> {
        val headersToStrip = HOP_BY_HOP_HEADERS + connectionNominatedHeaderNames()
        val outboundHeaders = linkedMapOf<String, List<String>>("host" to listOf(proxyRequest.forwardHostHeaderValue()))
        headers.forEach { (headerName, values) ->
            if (headerName.lowercase(Locale.US) !in headersToStrip) {
                outboundHeaders[headerName] = values
            }
        }
        return outboundHeaders
    }

    private fun ParsedProxyRequest.HttpProxy.forwardHostHeaderValue(): String = if (port == HTTP_DEFAULT_PORT) host else "$host:$port"

    private fun ParsedHttpRequest.connectionNominatedHeaderNames(): Set<String> =
        headers
            .filterKeys { headerName -> headerName.lowercase(Locale.US) == CONNECTION_HEADER }
            .values
            .flatten()
            .flatMap { value -> value.split(',') }
            .map { option -> option.trim().lowercase(Locale.US) }
            .filter { option -> option.isHttpToken() }
            .toSet()
}

private fun String.isSafeSingleLine(): Boolean = isNotEmpty() && none(Char::isISOControl)

private fun String.isHttpToken(): Boolean = isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun String.isDecimalDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun Char.isDisallowedHeaderValueControl(): Boolean = code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private fun copySafeForwardedRequestHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
    val copiedHeaders = linkedMapOf<String, List<String>>()
    headers.forEach { (name, values) ->
        require(name.isHttpToken()) { "Header name must be a valid HTTP token" }
        require(values.isNotEmpty()) { "Header values must not be empty" }
        copiedHeaders[name] =
            Collections.unmodifiableList(
                values.map { value ->
                    require(value.none(Char::isDisallowedHeaderValueControl)) {
                        "Header value must not contain control characters"
                    }
                    value
                },
            )
    }
    val normalizedHeaderNames = copiedHeaders.keys.map { it.lowercase(Locale.US) }
    require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
        "HTTP header names must not contain case-variant duplicates"
    }
    require(TRANSFER_ENCODING_HEADER !in normalizedHeaderNames) {
        "Transfer-Encoding is not supported for forwarded fixed-length requests"
    }

    val contentLengthValues = copiedHeaders.caseInsensitiveHeaderValues(CONTENT_LENGTH_HEADER)
    require(contentLengthValues.size <= 1) {
        "Forwarded requests must not contain duplicate Content-Length headers"
    }
    contentLengthValues.singleOrNull()?.let { contentLength ->
        require(contentLength.isDecimalDigits()) {
            "Content-Length must be a non-negative decimal value"
        }
        require(contentLength.toLongOrNull() != null) {
            "Content-Length must fit in a signed 64-bit integer"
        }
    }

    return Collections.unmodifiableMap(copiedHeaders)
}

private fun renderRequestHeaderString(
    requestLine: String,
    headers: Map<String, List<String>>,
): String =
    buildString {
        append(requestLine).append(CRLF)
        headers.forEach { (name, values) ->
            values.forEach { value ->
                append(name).append(": ").append(value).append(CRLF)
            }
        }
        append(CRLF)
    }

private fun Map<String, List<String>>.caseInsensitiveHeaderValues(name: String): List<String> {
    val normalizedName = name.lowercase(Locale.US)
    return entries
        .filter { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }
        .flatMap { (_, values) -> values }
}

private fun Map<String, List<String>>.containsHeader(name: String): Boolean = caseInsensitiveHeaderValues(name).isNotEmpty()

private fun ByteArray.decodeStrictUtf8(): String =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalStateException("Forwarded request body is not valid UTF-8; use toByteArray() for byte-accurate rendering")
    }

private const val CRLF = "\r\n"
private const val DELETE_CONTROL_CHAR = 0x7F
private const val HTTP_DEFAULT_PORT = 80
private const val CONNECTION_HEADER = "connection"
private const val CONTENT_LENGTH_HEADER = "content-length"
private const val TRANSFER_ENCODING_HEADER = "transfer-encoding"
private val EMPTY_BODY = ByteArray(0)
private val VALID_PORT_RANGE = 1..65535
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HOP_BY_HOP_HEADERS =
    setOf(
        "connection",
        "host",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )
private val HTTP_TOKEN_CHARS =
    (
        ('0'..'9') +
            ('A'..'Z') +
            ('a'..'z') +
            listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
    ).toSet()
