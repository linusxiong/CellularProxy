package com.cellularproxy.proxy.admission

import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationPolicy
import com.cellularproxy.shared.proxy.ProxyAuthenticationRejectionReason
import java.security.MessageDigest
import java.util.Locale

data class ProxyRequestAdmissionConfig(
    val proxyAuthentication: ProxyAuthenticationConfig,
    val managementApiToken: String,
) {
    init {
        require(managementApiToken.isNotBlank()) { "Management API token must not be blank" }
    }

    override fun toString(): String =
        "ProxyRequestAdmissionConfig(proxyAuthentication=[REDACTED], managementApiToken=[REDACTED])"
}

sealed interface ProxyRequestAdmissionDecision {
    data class Accepted(
        val request: ParsedProxyRequest,
        val requiresAuditLog: Boolean,
    ) : ProxyRequestAdmissionDecision

    data class Rejected(
        val reason: ProxyRequestAdmissionRejectionReason,
    ) : ProxyRequestAdmissionDecision
}

sealed interface ProxyRequestAdmissionRejectionReason {
    data object DuplicateProxyAuthorizationHeader : ProxyRequestAdmissionRejectionReason

    data class ProxyAuthentication(
        val reason: ProxyAuthenticationRejectionReason,
    ) : ProxyRequestAdmissionRejectionReason

    data class ManagementAuthorization(
        val reason: ManagementAuthorizationRejectionReason,
    ) : ProxyRequestAdmissionRejectionReason
}

enum class ManagementAuthorizationRejectionReason {
    MissingAuthorization,
    DuplicateAuthorizationHeader,
    UnsupportedScheme,
    MalformedBearerToken,
    TokenMismatch,
}

object ProxyRequestAdmissionPolicy {
    fun evaluate(
        config: ProxyRequestAdmissionConfig,
        request: ParsedHttpRequest,
    ): ProxyRequestAdmissionDecision =
        when (val parsedRequest = request.request) {
            is ParsedProxyRequest.HttpProxy,
            is ParsedProxyRequest.ConnectTunnel,
            -> evaluateProxyRequest(config, request)
            is ParsedProxyRequest.Management ->
                evaluateManagementRequest(config, parsedRequest, request.headers)
        }

    private fun evaluateProxyRequest(
        config: ProxyRequestAdmissionConfig,
        request: ParsedHttpRequest,
    ): ProxyRequestAdmissionDecision {
        val proxyAuthorization = when (val header = request.headers.singleHeaderValue(PROXY_AUTHORIZATION_HEADER)) {
            HeaderLookup.Duplicate -> {
                return ProxyRequestAdmissionDecision.Rejected(
                    ProxyRequestAdmissionRejectionReason.DuplicateProxyAuthorizationHeader,
                )
            }
            HeaderLookup.Missing -> null
            is HeaderLookup.Present -> header.value
        }

        val authenticationDecision = ProxyAuthenticationPolicy.evaluate(
            config = config.proxyAuthentication,
            proxyAuthorization = proxyAuthorization,
        )
        return if (authenticationDecision.accepted) {
            ProxyRequestAdmissionDecision.Accepted(
                request = request.request,
                requiresAuditLog = false,
            )
        } else {
            ProxyRequestAdmissionDecision.Rejected(
                ProxyRequestAdmissionRejectionReason.ProxyAuthentication(
                    authenticationDecision.rejectionReason
                        ?: error("Rejected proxy authentication decisions must carry a reason"),
                ),
            )
        }
    }

    private fun evaluateManagementRequest(
        config: ProxyRequestAdmissionConfig,
        request: ParsedProxyRequest.Management,
        headers: Map<String, List<String>>,
    ): ProxyRequestAdmissionDecision {
        val authorization = when (val header = headers.singleHeaderValue(AUTHORIZATION_HEADER)) {
            HeaderLookup.Duplicate ->
                return rejectedManagement(ManagementAuthorizationRejectionReason.DuplicateAuthorizationHeader)
            HeaderLookup.Missing -> null
            is HeaderLookup.Present -> header.value.trim()
        }

        if (!request.requiresToken) {
            return ProxyRequestAdmissionDecision.Accepted(
                request = request,
                requiresAuditLog = request.requiresAuditLog,
            )
        }

        if (authorization == null) {
            return rejectedManagement(ManagementAuthorizationRejectionReason.MissingAuthorization)
        }

        val firstWhitespace = authorization.indexOfFirst(Char::isWhitespace)
        if (firstWhitespace == -1) {
            return if (authorization.equals(BEARER_SCHEME, ignoreCase = true)) {
                rejectedManagement(ManagementAuthorizationRejectionReason.MalformedBearerToken)
            } else {
                rejectedManagement(ManagementAuthorizationRejectionReason.UnsupportedScheme)
            }
        }

        val scheme = authorization.substring(0, firstWhitespace)
        if (!scheme.equals(BEARER_SCHEME, ignoreCase = true)) {
            return rejectedManagement(ManagementAuthorizationRejectionReason.UnsupportedScheme)
        }

        val token = authorization.substring(firstWhitespace).trim()
        if (token.isEmpty()) {
            return rejectedManagement(ManagementAuthorizationRejectionReason.MalformedBearerToken)
        }

        return if (ConstantTimeSecret.equalsUtf8(token, config.managementApiToken)) {
            ProxyRequestAdmissionDecision.Accepted(
                request = request,
                requiresAuditLog = request.requiresAuditLog,
            )
        } else {
            rejectedManagement(ManagementAuthorizationRejectionReason.TokenMismatch)
        }
    }

    private fun rejectedManagement(
        reason: ManagementAuthorizationRejectionReason,
    ): ProxyRequestAdmissionDecision.Rejected =
        ProxyRequestAdmissionDecision.Rejected(
            ProxyRequestAdmissionRejectionReason.ManagementAuthorization(reason),
        )
}

private sealed interface HeaderLookup {
    data object Missing : HeaderLookup
    data object Duplicate : HeaderLookup
    data class Present(val value: String) : HeaderLookup
}

private fun Map<String, List<String>>.singleHeaderValue(name: String): HeaderLookup {
    val normalizedName = name.lowercase(Locale.US)
    val values = entries
        .filter { (headerName, _) -> headerName.lowercase(Locale.US) == normalizedName }
        .flatMap { (_, headerValues) -> headerValues }

    return when (values.size) {
        0 -> HeaderLookup.Missing
        1 -> HeaderLookup.Present(values.single())
        else -> HeaderLookup.Duplicate
    }
}

internal object ConstantTimeSecret {
    fun equalsUtf8(supplied: String, expected: String): Boolean {
        val suppliedBytes = supplied.toByteArray(Charsets.UTF_8)
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(suppliedBytes, expectedBytes)
    }
}

private const val PROXY_AUTHORIZATION_HEADER = "proxy-authorization"
private const val AUTHORIZATION_HEADER = "authorization"
private const val BEARER_SCHEME = "Bearer"
