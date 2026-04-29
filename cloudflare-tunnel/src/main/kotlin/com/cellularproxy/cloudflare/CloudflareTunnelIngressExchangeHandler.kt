package com.cellularproxy.cloudflare

import com.cellularproxy.shared.management.CloudflareIngressDecision
import com.cellularproxy.shared.management.CloudflareManagementIngressPolicy
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.management.ManagementIngressRequest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

private val HTTP_STATUS_CODE_RANGE = 100..599

fun interface CloudflareLocalManagementHandler {
    fun handle(request: CloudflareLocalManagementRequest): CloudflareTunnelResponse
}

class CloudflareLocalManagementRequest(
    val method: HttpMethod,
    val originTarget: String,
    headers: Map<String, List<String>> = emptyMap(),
    body: ByteArray = byteArrayOf(),
) {
    val headers: Map<String, List<String>> = headers.toDefensiveHeaders()
    private val bodyBytes: ByteArray = body.copyOf()
    val body: ByteArray
        get() = bodyBytes.copyOf()

    init {
        require(
            CloudflareManagementIngressPolicy.evaluate(
                ManagementIngressRequest.OriginForm(method = method, path = originTarget),
            ) == CloudflareIngressDecision.Forward,
        ) {
            "Cloudflare forwarded management requests must target allowed management paths"
        }
        require(originTarget.isOriginFormTarget()) {
            "Cloudflare forwarded management request targets must be valid origin-form paths"
        }
        require(!this.headers.containsRequestHeader(TRANSFER_ENCODING_HEADER)) {
            "Transfer-Encoding is not supported for Cloudflare forwarded management requests"
        }
        val contentLengthValues = this.headers.requestHeaderValues(CONTENT_LENGTH_HEADER)
        require(bodyBytes.isEmpty() || contentLengthValues != null) {
            "Non-empty Cloudflare forwarded management requests must include Content-Length"
        }
        contentLengthValues?.let { values ->
            require(values.size == 1) {
                "Cloudflare forwarded management requests must have one Content-Length"
            }
            val contentLength = values.single().parseUnsignedDecimalContentLength()
            require(contentLength != null) {
                "Content-Length must be a non-negative decimal value"
            }
            require(contentLength == bodyBytes.size.toLong()) {
                "Content-Length must match the forwarded management request body length"
            }
        }
    }

    override fun toString(): String = "CloudflareLocalManagementRequest(method=$method, originTarget=<redacted>)"
}

sealed interface CloudflareTunnelIngressResult {
    val response: CloudflareTunnelResponse

    class Forwarded(
        override val response: CloudflareTunnelResponse,
    ) : CloudflareTunnelIngressResult {
        override fun toString(): String = "Forwarded(statusCode=${response.statusCode})"
    }

    class Rejected(
        override val response: CloudflareTunnelResponse,
    ) : CloudflareTunnelIngressResult {
        override fun toString(): String = "Rejected(statusCode=${response.statusCode})"
    }
}

class CloudflareTunnelResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    headers: Map<String, String>,
    val body: String,
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    init {
        require(statusCode in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
        require(reasonPhrase.isNotBlank()) { "HTTP reason phrase must not be blank" }
        require(reasonPhrase.none { it.isUnsafeHttpHeaderCharacter() }) {
            "HTTP reason phrase must not contain control characters"
        }
        this.headers.forEach { (name, value) ->
            require(name.isHttpToken()) { "HTTP header names must be valid tokens" }
            require(value.none { it.isUnsafeHttpHeaderCharacter() }) {
                "HTTP header values must not contain control characters"
            }
        }
        val normalizedHeaderNames = this.headers.keys.map { it.lowercase(Locale.US) }
        require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
            "HTTP header names must not contain case-variant duplicates"
        }
        require(!this.headers.containsHeader(TRANSFER_ENCODING_HEADER)) {
            "Transfer-Encoding is not supported for Cloudflare tunnel responses"
        }
        val contentLengthHeader = this.headers.headerValue(CONTENT_LENGTH_HEADER)
        require(body.isEmpty() || contentLengthHeader != null) {
            "Non-empty Cloudflare tunnel responses must include Content-Length"
        }
        contentLengthHeader?.let { contentLength ->
            val contentLengthBytes = contentLength.parseUnsignedDecimalContentLength()
            require(contentLengthBytes != null) {
                "Content-Length must be a non-negative decimal value"
            }
            require(contentLengthBytes == body.toByteArray(Charsets.UTF_8).size.toLong()) {
                "Content-Length must match the UTF-8 response body length"
            }
        }
    }

    override fun toString(): String = "CloudflareTunnelResponse(statusCode=$statusCode)"

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
        ): CloudflareTunnelResponse {
            val headers =
                linkedMapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Content-Length" to body.toByteArray(Charsets.UTF_8).size.toString(),
                    "Cache-Control" to "no-store",
                )

            return CloudflareTunnelResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhraseFor(statusCode),
                headers = headers,
                body = body,
            )
        }

        fun empty(statusCode: Int): CloudflareTunnelResponse = CloudflareTunnelResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhraseFor(statusCode),
            headers =
                linkedMapOf(
                    "Content-Length" to "0",
                    "Cache-Control" to "no-store",
                ),
            body = "",
        )
    }
}

object CloudflareTunnelIngressExchangeHandler {
    fun handle(
        method: HttpMethod,
        target: String,
        localManagementHandler: CloudflareLocalManagementHandler,
    ): CloudflareTunnelIngressResult = handle(
        method = method,
        target = target,
        requestHeaders = emptyMap(),
        requestBody = byteArrayOf(),
        localManagementHandler = localManagementHandler,
    )

    fun handle(
        method: HttpMethod,
        target: String,
        requestHeaders: Map<String, List<String>> = emptyMap(),
        requestBody: ByteArray = byteArrayOf(),
        localManagementHandler: CloudflareLocalManagementHandler,
    ): CloudflareTunnelIngressResult {
        val ingressRequest = target.toManagementIngressRequest(method)

        return when (CloudflareManagementIngressPolicy.evaluate(ingressRequest)) {
            CloudflareIngressDecision.Forward -> {
                val originForm = ingressRequest as ManagementIngressRequest.OriginForm
                val request =
                    try {
                        CloudflareLocalManagementRequest(
                            method = originForm.method,
                            originTarget = originForm.path,
                            headers = requestHeaders,
                            body = requestBody,
                        )
                    } catch (_: IllegalArgumentException) {
                        return CloudflareTunnelIngressResult.Rejected(forbiddenResponse())
                    }
                CloudflareTunnelIngressResult.Forwarded(
                    localManagementHandler.handle(request),
                )
            }
            CloudflareIngressDecision.Reject ->
                CloudflareTunnelIngressResult.Rejected(forbiddenResponse())
        }
    }
}

private fun forbiddenResponse(): CloudflareTunnelResponse = CloudflareTunnelResponse.json(
    statusCode = 403,
    body = """{"error":"forbidden"}""",
)

private fun String.toManagementIngressRequest(method: HttpMethod): ManagementIngressRequest = when {
    method == HttpMethod.Connect -> ManagementIngressRequest.ConnectAuthority(this)
    isExplicitProxyForm() -> ManagementIngressRequest.ExplicitProxyForm(this)
    !isOriginFormTarget() -> ManagementIngressRequest.MalformedTarget
    else -> ManagementIngressRequest.OriginForm(method = method, path = this)
}

private fun String.isExplicitProxyForm(): Boolean = contains("://")

private fun String.isOriginFormTarget(): Boolean = startsWith("/") &&
    none { it.isUnsafeOriginTargetCharacter() } &&
    !contains('#')

private fun Char.isUnsafeOriginTargetCharacter(): Boolean = isWhitespace() || isUnsafeHttpHeaderCharacter()

private fun Map<String, List<String>>.toDefensiveHeaders(): Map<String, List<String>> = Collections.unmodifiableMap(
    LinkedHashMap<String, List<String>>().also { copiedHeaders ->
        forEach { (name, values) ->
            require(name.isHttpToken()) { "HTTP header names must be valid tokens" }
            val copiedValues = values.toList()
            copiedValues.forEach { value ->
                require(value.none { it.isUnsafeHttpHeaderCharacter() }) {
                    "HTTP header values must not contain control characters"
                }
            }
            copiedHeaders[name] = Collections.unmodifiableList(copiedValues)
        }
        val normalizedHeaderNames = copiedHeaders.keys.map { it.lowercase(Locale.US) }
        require(normalizedHeaderNames.size == normalizedHeaderNames.toSet().size) {
            "HTTP header names must not contain case-variant duplicates"
        }
    },
)

private fun String.isHttpToken(): Boolean = isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Map<String, String>.containsHeader(name: String): Boolean = headerValue(name) != null

private fun Map<String, List<String>>.containsRequestHeader(name: String): Boolean = requestHeaderValues(name) != null

private fun Map<String, String>.headerValue(name: String): String? {
    val normalizedName = name.lowercase(Locale.US)
    return entries.singleOrNull { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }?.value
}

private fun Map<String, List<String>>.requestHeaderValues(name: String): List<String>? {
    val normalizedName = name.lowercase(Locale.US)
    return entries.singleOrNull { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }?.value
}

private fun Char.isUnsafeHttpHeaderCharacter(): Boolean = code in CONTROL_CHAR_RANGE || code == DELETE_CONTROL_CHAR

private fun String.parseUnsignedDecimalContentLength(): Long? = takeIf {
    it.isNotEmpty() && it.all { character -> character in '0'..'9' }
}?.toLongOrNull()

private fun reasonPhraseFor(statusCode: Int): String = when (statusCode) {
    200 -> "OK"
    202 -> "Accepted"
    204 -> "No Content"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    500 -> "Internal Server Error"
    else -> "Status"
}

private const val CONTENT_LENGTH_HEADER = "Content-Length"
private const val TRANSFER_ENCODING_HEADER = "Transfer-Encoding"
private const val CRLF = "\r\n"
private const val DELETE_CONTROL_CHAR = 0x7F
private val CONTROL_CHAR_RANGE = 0x00..0x1F
private val HTTP_TOKEN_CHARS =
    (
        ('0'..'9') +
            ('A'..'Z') +
            ('a'..'z') +
            listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
    ).toSet()
