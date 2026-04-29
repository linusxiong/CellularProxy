package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class PublicIpProbeEndpoint(
    val host: String,
    val scheme: PublicIpProbeScheme = PublicIpProbeScheme.Http,
    val port: Int = scheme.defaultPort,
    val path: String = DEFAULT_PUBLIC_IP_PROBE_PATH,
    val responseFormat: PublicIpProbeResponseFormat = PublicIpProbeResponseFormat.PlainText,
    val timeoutMillis: Long = DEFAULT_PUBLIC_IP_PROBE_TIMEOUT_MILLIS,
    val maxResponseBytes: Int = DEFAULT_PUBLIC_IP_PROBE_MAX_RESPONSE_BYTES,
) {
    init {
        require(host.isNotBlank()) { "Probe host must not be blank" }
        require(host.none { it.isWhitespace() || it.isISOControl() }) {
            "Probe host must not contain whitespace or control characters"
        }
        require(host.all { it.code in ASCII_PRINTABLE_RANGE }) { "Probe host must be ASCII" }
        require(!host.startsWith("[") && !host.endsWith("]")) {
            "Probe host must not include IPv6 brackets"
        }
        require(port in VALID_PORT_RANGE) { "Probe port must be in range 1..65535" }
        require(path.startsWith("/")) { "Probe path must be origin-form" }
        require(path.none { it.isWhitespace() || it.isISOControl() }) {
            "Probe path must not contain whitespace or control characters"
        }
        require(path.all { it.code in ASCII_PRINTABLE_RANGE }) { "Probe path must be ASCII" }
        require(timeoutMillis in 1..Int.MAX_VALUE.toLong()) {
            "Probe timeout must be in range 1..${Int.MAX_VALUE}"
        }
        require(maxResponseBytes > 0) { "Maximum probe response bytes must be positive" }
    }
}

enum class PublicIpProbeScheme(
    internal val defaultPort: Int,
) {
    Http(80),
    Https(443),
}

enum class PublicIpProbeResponseFormat {
    PlainText,
    JsonIpField,
}

class RouteBoundPublicIpProbe(
    private val socketProvider: BoundSocketProvider,
) : PublicIpProbeRunner {
    override suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult = when (
        val connectResult =
            socketProvider.connect(
                route = route,
                host = endpoint.host,
                port = endpoint.port,
                timeoutMillis = endpoint.timeoutMillis,
            )
    ) {
        is BoundSocketConnectResult.Failed ->
            PublicIpProbeResult.Failed(connectResult.reason.toPublicIpProbeFailure())
        is BoundSocketConnectResult.Connected ->
            probeConnectedSocket(
                socket = connectResult.socket,
                network = connectResult.network,
                endpoint = endpoint,
            )
    }

    private fun probeConnectedSocket(
        socket: Socket,
        network: NetworkDescriptor,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult = try {
        val requestSocket = socket.wrapForScheme(endpoint)
        requestSocket.soTimeout = endpoint.timeoutMillis.toInt()
        requestSocket.getOutputStream().write(endpoint.toHttpRequestBytes())
        requestSocket.getOutputStream().flush()

        parseProbeResponse(
            bytes = requestSocket.readResponseBytes(endpoint.maxResponseBytes),
            endpoint = endpoint,
            network = network,
        )
    } catch (_: SocketTimeoutException) {
        PublicIpProbeResult.Failed(PublicIpProbeFailure.ResponseTimedOut, network)
    } catch (_: PublicIpProbeResponseTooLargeException) {
        PublicIpProbeResult.Failed(PublicIpProbeFailure.ResponseTooLarge, network)
    } catch (_: Exception) {
        PublicIpProbeResult.Failed(PublicIpProbeFailure.IoFailure, network)
    } finally {
        socket.closeQuietly()
    }
}

sealed interface PublicIpProbeResult {
    data class Success(
        val publicIp: String,
        val network: NetworkDescriptor,
    ) : PublicIpProbeResult {
        init {
            require(isValidPublicIp(publicIp)) { "Public IP must be a numeric IPv4 or IPv6 address" }
            require(network.isAvailable) { "Public IP probe network must be available" }
        }
    }

    data class Failed(
        val reason: PublicIpProbeFailure,
        val network: NetworkDescriptor? = null,
    ) : PublicIpProbeResult {
        init {
            require(network?.isAvailable != false) { "Failed probe network metadata must be available when present" }
        }
    }
}

enum class PublicIpProbeFailure {
    SelectedRouteUnavailable,
    DnsResolutionFailed,
    ConnectionFailed,
    ConnectionTimedOut,
    ResponseTimedOut,
    IoFailure,
    ResponseTooLarge,
    MalformedHttpResponse,
    NonSuccessStatus,
    InvalidPublicIp,
}

private fun BoundSocketConnectFailure.toPublicIpProbeFailure(): PublicIpProbeFailure = when (this) {
    BoundSocketConnectFailure.SelectedRouteUnavailable -> PublicIpProbeFailure.SelectedRouteUnavailable
    BoundSocketConnectFailure.DnsResolutionFailed -> PublicIpProbeFailure.DnsResolutionFailed
    BoundSocketConnectFailure.ConnectionFailed -> PublicIpProbeFailure.ConnectionFailed
    BoundSocketConnectFailure.ConnectionTimedOut -> PublicIpProbeFailure.ConnectionTimedOut
}

private fun PublicIpProbeEndpoint.toHttpRequestBytes(): ByteArray = (
    "GET $path HTTP/1.1\r\n" +
        "Host: ${hostHeaderValue()}\r\n" +
        "Accept: ${responseFormat.acceptHeaderValue}\r\n" +
        "Connection: close\r\n" +
        "\r\n"
).toByteArray(Charsets.US_ASCII)

private fun PublicIpProbeEndpoint.hostHeaderValue(): String = hostForHeader().let { hostForHeader ->
    if (port == scheme.defaultPort) hostForHeader else "$hostForHeader:$port"
}

private fun PublicIpProbeEndpoint.hostForHeader(): String = if (host.contains(":")) "[$host]" else host

private val PublicIpProbeResponseFormat.acceptHeaderValue: String
    get() =
        when (this) {
            PublicIpProbeResponseFormat.PlainText -> "text/plain"
            PublicIpProbeResponseFormat.JsonIpField -> "application/json"
        }

private fun Socket.wrapForScheme(endpoint: PublicIpProbeEndpoint): Socket = when (endpoint.scheme) {
    PublicIpProbeScheme.Http -> this
    PublicIpProbeScheme.Https ->
        (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(this, endpoint.host, endpoint.port, true)
            .also { socket ->
                (socket as SSLSocket).useClientMode = true
                socket.startHandshake()
            }
}

private fun Socket.readResponseBytes(maxResponseBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_PUBLIC_IP_PROBE_READ_BUFFER_BYTES)
    while (true) {
        val read = getInputStream().read(buffer)
        if (read == -1) {
            return output.toByteArray()
        }
        if (output.size() + read > maxResponseBytes) {
            throw PublicIpProbeResponseTooLargeException()
        }
        output.write(buffer, 0, read)
    }
}

private fun parseProbeResponse(
    bytes: ByteArray,
    endpoint: PublicIpProbeEndpoint,
    network: NetworkDescriptor,
): PublicIpProbeResult {
    val headerEnd =
        findHeaderEnd(bytes)
            ?: return PublicIpProbeResult.Failed(PublicIpProbeFailure.MalformedHttpResponse, network)
    val headerText =
        bytes
            .copyOfRange(0, headerEnd.headerBytesEnd)
            .toString(Charsets.US_ASCII)
    val statusCode =
        parseStatusCode(headerText.lineSequence().firstOrNull())
            ?: return PublicIpProbeResult.Failed(PublicIpProbeFailure.MalformedHttpResponse, network)
    if (statusCode !in SUCCESS_STATUS_RANGE) {
        return PublicIpProbeResult.Failed(PublicIpProbeFailure.NonSuccessStatus, network)
    }

    val bodyBytes =
        bytes.copyOfRange(headerEnd.bodyStart, bytes.size)
    val decodedBodyBytes =
        if (headerText.hasChunkedTransferEncoding()) {
            decodeChunkedBody(bodyBytes)
                ?: return PublicIpProbeResult.Failed(PublicIpProbeFailure.MalformedHttpResponse, network)
        } else {
            bodyBytes
        }
    val body = decodedBodyBytes.toString(Charsets.UTF_8).trim()
    val publicIp = endpoint.responseFormat.publicIpFromBody(body)
    if (!isValidPublicIp(publicIp)) {
        return PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp, network)
    }

    return PublicIpProbeResult.Success(
        publicIp = publicIp,
        network = network,
    )
}

private fun String.hasChunkedTransferEncoding(): Boolean = lineSequence()
    .drop(1)
    .mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator == -1) {
            null
        } else {
            line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
    }.any { (name, value) ->
        name.equals("Transfer-Encoding", ignoreCase = true) &&
            value.split(",").any { it.trim().equals("chunked", ignoreCase = true) }
    }

private fun decodeChunkedBody(bytes: ByteArray): ByteArray? {
    val output = ByteArrayOutputStream()
    var index = 0
    while (true) {
        val lineEnd = bytes.indexOfCrlf(index) ?: return null
        val sizeLine =
            bytes
                .copyOfRange(index, lineEnd)
                .toString(Charsets.US_ASCII)
                .substringBefore(';')
                .trim()
        val size = sizeLine.toIntOrNull(radix = 16) ?: return null
        index = lineEnd + CRLF_BYTES.size
        if (size == 0) {
            return output.toByteArray()
        }
        if (index + size > bytes.size) {
            return null
        }
        output.write(bytes, index, size)
        index += size
        if (!bytes.matchesAt(CRLF_BYTES, index)) {
            return null
        }
        index += CRLF_BYTES.size
    }
}

private fun ByteArray.indexOfCrlf(startIndex: Int): Int? {
    for (index in startIndex..size - CRLF_BYTES.size) {
        if (matchesAt(CRLF_BYTES, index)) {
            return index
        }
    }
    return null
}

private fun PublicIpProbeResponseFormat.publicIpFromBody(body: String): String = when (this) {
    PublicIpProbeResponseFormat.PlainText -> body
    PublicIpProbeResponseFormat.JsonIpField -> body.findFirstJsonStringField("ip", "publicIp", "query").orEmpty()
}

private fun String.findFirstJsonStringField(vararg fieldNames: String): String? {
    for (fieldName in fieldNames) {
        val match =
            Regex(
                pattern = """"${Regex.escape(fieldName)}"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""",
            ).find(this)
        if (match != null) {
            return match.groupValues[1].unescapeMinimalJsonString()
        }
    }
    return null
}

private fun String.unescapeMinimalJsonString(): String {
    if (!contains('\\')) {
        return this
    }
    val output = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char != '\\' || index == lastIndex) {
            output.append(char)
            index += 1
            continue
        }
        val escaped = this[index + 1]
        output.append(
            when (escaped) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> escaped
            },
        )
        index += 2
    }
    return output.toString()
}

private data class HeaderEnd(
    val headerBytesEnd: Int,
    val bodyStart: Int,
)

private fun findHeaderEnd(bytes: ByteArray): HeaderEnd? {
    for (index in 0..bytes.size - CRLF_HEADER_TERMINATOR.size) {
        if (bytes.matchesAt(CRLF_HEADER_TERMINATOR, index)) {
            return HeaderEnd(headerBytesEnd = index, bodyStart = index + CRLF_HEADER_TERMINATOR.size)
        }
    }
    for (index in 0..bytes.size - LF_HEADER_TERMINATOR.size) {
        if (bytes.matchesAt(LF_HEADER_TERMINATOR, index)) {
            return HeaderEnd(headerBytesEnd = index, bodyStart = index + LF_HEADER_TERMINATOR.size)
        }
    }
    return null
}

private fun ByteArray.matchesAt(
    needle: ByteArray,
    index: Int,
): Boolean {
    for (needleIndex in needle.indices) {
        if (this[index + needleIndex] != needle[needleIndex]) {
            return false
        }
    }
    return true
}

private fun parseStatusCode(statusLine: String?): Int? {
    if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
        return null
    }
    val parts = statusLine.split(" ", limit = 3)
    return parts.getOrNull(1)?.toIntOrNull()
}

private fun isValidPublicIp(value: String): Boolean = isValidIpv4(value) || isValidIpv6(value)

private fun isValidIpv4(value: String): Boolean {
    val octets = value.split(".")
    return octets.size == 4 &&
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull() in 0..255
        }
}

private fun isValidIpv6(value: String): Boolean {
    if (!value.contains(":") || value.any { it == '%' || it.isWhitespace() }) {
        return false
    }
    if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
        return false
    }
    return try {
        InetAddress.getByName(value) is Inet6Address
    } catch (_: Exception) {
        false
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Probe cleanup failures should not replace the probe outcome.
    }
}

private class PublicIpProbeResponseTooLargeException : RuntimeException()

private const val DEFAULT_PUBLIC_IP_PROBE_PATH = "/"
private const val DEFAULT_PUBLIC_IP_PROBE_TIMEOUT_MILLIS = 5_000L
private const val DEFAULT_PUBLIC_IP_PROBE_MAX_RESPONSE_BYTES = 8 * 1024
private const val DEFAULT_PUBLIC_IP_PROBE_READ_BUFFER_BYTES = 1024
private val ASCII_PRINTABLE_RANGE = 0x21..0x7E
private val VALID_PORT_RANGE = 1..65_535
private val SUCCESS_STATUS_RANGE = 200..299
private val CRLF_BYTES = "\r\n".toByteArray(Charsets.US_ASCII)
private val CRLF_HEADER_TERMINATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
private val LF_HEADER_TERMINATOR = "\n\n".toByteArray(Charsets.US_ASCII)
