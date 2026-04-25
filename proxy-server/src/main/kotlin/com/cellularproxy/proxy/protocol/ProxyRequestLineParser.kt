package com.cellularproxy.proxy.protocol

import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.management.ManagementAccessPolicy
import java.net.URI
import java.net.URISyntaxException

sealed interface ParsedProxyRequest {
    data class HttpProxy(
        val method: String,
        val host: String,
        val port: Int,
        val originTarget: String,
    ) : ParsedProxyRequest

    data class ConnectTunnel(
        val host: String,
        val port: Int,
    ) : ParsedProxyRequest

    data class Management(
        val method: HttpMethod,
        val originTarget: String,
        val requiresToken: Boolean,
        val requiresAuditLog: Boolean,
    ) : ParsedProxyRequest
}

sealed interface ProxyRequestLineParseResult {
    data class Accepted(val request: ParsedProxyRequest) : ProxyRequestLineParseResult
    data class Rejected(val reason: ProxyRequestLineRejectionReason) : ProxyRequestLineParseResult
}

enum class ProxyRequestLineRejectionReason {
    MalformedRequestLine,
    UnsupportedHttpVersion,
    InvalidAbsoluteUri,
    UnsupportedProxyScheme,
    InvalidConnectAuthority,
    UnsupportedOriginFormTarget,
    UnsupportedManagementMethod,
}

object ProxyRequestLineParser {
    fun parse(requestLine: String): ProxyRequestLineParseResult {
        if (requestLine.any(Char::isISOControl)) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.MalformedRequestLine)
        }

        val parts = requestLine.split(' ')
        if (parts.size != REQUEST_LINE_PART_COUNT || parts.any(String::isEmpty)) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.MalformedRequestLine)
        }

        val (method, target, version) = parts
        if (version != SUPPORTED_HTTP_VERSION) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedHttpVersion)
        }

        return when {
            method == CONNECT_METHOD ->
                parseConnectAuthority(target)
            target.startsWith("/") ->
                parseOriginForm(method, target)
            else ->
                parseAbsoluteForm(method, target)
        }
    }

    private fun parseAbsoluteForm(method: String, target: String): ProxyRequestLineParseResult {
        val uri = try {
            URI(target)
        } catch (_: URISyntaxException) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri)
        }

        if (uri.scheme != HTTP_SCHEME) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedProxyScheme)
        }
        if (uri.fragment != null || uri.userInfo != null || uri.host.isNullOrBlank()) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri)
        }

        val port = if (uri.port == -1) HTTP_DEFAULT_PORT else uri.port
        if (port !in VALID_PORT_RANGE) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidAbsoluteUri)
        }
        val originTarget = buildOriginTarget(uri)

        return ProxyRequestLineParseResult.Accepted(
            ParsedProxyRequest.HttpProxy(
                method = method,
                host = uri.host,
                port = port,
                originTarget = originTarget,
            ),
        )
    }

    private fun parseConnectAuthority(target: String): ProxyRequestLineParseResult {
        if (target.any { it == '/' || it == '?' || it == '#' }) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidConnectAuthority)
        }

        val authorityUri = try {
            URI("$AUTHORITY_PARSE_SCHEME://$target")
        } catch (_: URISyntaxException) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidConnectAuthority)
        }

        val host = authorityUri.host.toBracketedIpv6HostIfNeeded()
        val port = authorityUri.port
        if (authorityUri.userInfo != null || host.isNullOrBlank() || port !in VALID_PORT_RANGE) {
            return ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.InvalidConnectAuthority)
        }

        return ProxyRequestLineParseResult.Accepted(
            ParsedProxyRequest.ConnectTunnel(
                host = host,
                port = port,
            ),
        )
    }

    private fun parseOriginForm(method: String, target: String): ProxyRequestLineParseResult {
        val managementMethod = when (method) {
            GET_METHOD -> HttpMethod.Get
            POST_METHOD -> HttpMethod.Post
            else -> null
        }

        if (managementMethod == null) {
            return if (target.isPotentialManagementTarget()) {
                ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedManagementMethod)
            } else {
                ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedOriginFormTarget)
            }
        }

        val decision = ManagementAccessPolicy.evaluate(managementMethod, target)
        return if (decision.isManagementRequest) {
            ProxyRequestLineParseResult.Accepted(
                ParsedProxyRequest.Management(
                    method = managementMethod,
                    originTarget = target,
                    requiresToken = decision.requiresToken,
                    requiresAuditLog = decision.requiresAuditLog,
                ),
            )
        } else {
            ProxyRequestLineParseResult.Rejected(ProxyRequestLineRejectionReason.UnsupportedOriginFormTarget)
        }
    }

    private fun buildOriginTarget(uri: URI): String {
        val path = uri.rawPath?.takeUnless(String::isEmpty) ?: "/"
        val query = uri.rawQuery
        return if (query == null) path else "$path?$query"
    }

    private fun String.isPotentialManagementTarget(): Boolean {
        val path = substringBefore('?').substringBefore('#')
        return path == HEALTH_PATH || path.startsWith(API_PREFIX)
    }

    private fun String?.toBracketedIpv6HostIfNeeded(): String? {
        if (this == null) {
            return null
        }
        return if (':' in this && !startsWith("[") && !endsWith("]")) {
            "[$this]"
        } else {
            this
        }
    }
}

private const val REQUEST_LINE_PART_COUNT = 3
private const val SUPPORTED_HTTP_VERSION = "HTTP/1.1"
private const val CONNECT_METHOD = "CONNECT"
private const val GET_METHOD = "GET"
private const val POST_METHOD = "POST"
private const val HTTP_SCHEME = "http"
private const val HTTP_DEFAULT_PORT = 80
private const val AUTHORITY_PARSE_SCHEME = "proxy"
private const val HEALTH_PATH = "/health"
private const val API_PREFIX = "/api/"
private val VALID_PORT_RANGE = 1..65535
