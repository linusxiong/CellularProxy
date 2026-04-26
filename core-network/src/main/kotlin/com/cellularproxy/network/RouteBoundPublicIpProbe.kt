package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class PublicIpProbeEndpoint(
    val host: String,
    val port: Int = DEFAULT_PUBLIC_IP_PROBE_PORT,
    val path: String = DEFAULT_PUBLIC_IP_PROBE_PATH,
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

class RouteBoundPublicIpProbe(
    private val socketProvider: BoundSocketProvider,
) : PublicIpProbeRunner {
    override suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult =
        when (
            val connectResult = socketProvider.connect(
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
    ): PublicIpProbeResult =
        try {
            socket.soTimeout = endpoint.timeoutMillis.toInt()
            socket.getOutputStream().write(endpoint.toHttpRequestBytes())
            socket.getOutputStream().flush()

            parseProbeResponse(
                bytes = socket.readResponseBytes(endpoint.maxResponseBytes),
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

private fun BoundSocketConnectFailure.toPublicIpProbeFailure(): PublicIpProbeFailure =
    when (this) {
        BoundSocketConnectFailure.SelectedRouteUnavailable -> PublicIpProbeFailure.SelectedRouteUnavailable
        BoundSocketConnectFailure.DnsResolutionFailed -> PublicIpProbeFailure.DnsResolutionFailed
        BoundSocketConnectFailure.ConnectionFailed -> PublicIpProbeFailure.ConnectionFailed
        BoundSocketConnectFailure.ConnectionTimedOut -> PublicIpProbeFailure.ConnectionTimedOut
    }

private fun PublicIpProbeEndpoint.toHttpRequestBytes(): ByteArray =
    (
        "GET $path HTTP/1.1\r\n" +
            "Host: ${hostHeaderValue()}\r\n" +
            "Accept: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)

private fun PublicIpProbeEndpoint.hostHeaderValue(): String =
    hostForHeader().let { hostForHeader ->
        if (port == DEFAULT_PUBLIC_IP_PROBE_PORT) hostForHeader else "$hostForHeader:$port"
    }

private fun PublicIpProbeEndpoint.hostForHeader(): String =
    if (host.contains(":")) "[$host]" else host

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
    network: NetworkDescriptor,
): PublicIpProbeResult {
    val headerEnd = findHeaderEnd(bytes)
        ?: return PublicIpProbeResult.Failed(PublicIpProbeFailure.MalformedHttpResponse, network)
    val headerText = bytes.copyOfRange(0, headerEnd.headerBytesEnd)
        .toString(Charsets.US_ASCII)
    val statusCode = parseStatusCode(headerText.lineSequence().firstOrNull())
        ?: return PublicIpProbeResult.Failed(PublicIpProbeFailure.MalformedHttpResponse, network)
    if (statusCode !in SUCCESS_STATUS_RANGE) {
        return PublicIpProbeResult.Failed(PublicIpProbeFailure.NonSuccessStatus, network)
    }

    val body = bytes.copyOfRange(headerEnd.bodyStart, bytes.size)
        .toString(Charsets.UTF_8)
        .trim()
    if (!isValidPublicIp(body)) {
        return PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp, network)
    }

    return PublicIpProbeResult.Success(
        publicIp = body,
        network = network,
    )
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

private fun isValidPublicIp(value: String): Boolean =
    isValidIpv4(value) || isValidIpv6(value)

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

private const val DEFAULT_PUBLIC_IP_PROBE_PORT = 80
private const val DEFAULT_PUBLIC_IP_PROBE_PATH = "/"
private const val DEFAULT_PUBLIC_IP_PROBE_TIMEOUT_MILLIS = 5_000L
private const val DEFAULT_PUBLIC_IP_PROBE_MAX_RESPONSE_BYTES = 8 * 1024
private const val DEFAULT_PUBLIC_IP_PROBE_READ_BUFFER_BYTES = 1024
private val ASCII_PRINTABLE_RANGE = 0x21..0x7E
private val VALID_PORT_RANGE = 1..65_535
private val SUCCESS_STATUS_RANGE = 200..299
private val CRLF_HEADER_TERMINATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
private val LF_HEADER_TERMINATOR = "\n\n".toByteArray(Charsets.US_ASCII)
