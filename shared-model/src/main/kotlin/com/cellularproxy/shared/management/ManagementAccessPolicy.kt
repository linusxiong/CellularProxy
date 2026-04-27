package com.cellularproxy.shared.management

enum class HttpMethod {
    Get,
    Post,
    Connect,
}

data class ManagementAccessDecision(
    val isManagementRequest: Boolean,
    val requiresToken: Boolean,
    val requiresAuditLog: Boolean,
)

object ManagementAccessPolicy {
    fun evaluate(
        method: HttpMethod,
        originTarget: String,
    ): ManagementAccessDecision {
        val path = originTarget.pathComponent()
        val isPublicHealth = method == HttpMethod.Get && path == HEALTH_PATH
        val isApiMethod = method == HttpMethod.Get || method == HttpMethod.Post
        val isApiPath = isApiMethod && path.startsWith(API_PREFIX)
        val requiresAuditLog = method to path in HIGH_IMPACT_ENDPOINTS
        val isManagementRequest = isPublicHealth || isApiPath

        return ManagementAccessDecision(
            isManagementRequest = isManagementRequest,
            requiresToken = isApiPath,
            requiresAuditLog = requiresAuditLog,
        )
    }
}

enum class CloudflareIngressDecision {
    Forward,
    Reject,
}

sealed interface ManagementIngressRequest {
    data class OriginForm(
        val method: HttpMethod,
        val path: String,
    ) : ManagementIngressRequest {
        override fun toString(): String = "OriginForm(method=$method, path=<redacted>)"
    }

    data class ExplicitProxyForm(
        val target: String,
    ) : ManagementIngressRequest {
        override fun toString(): String = "ExplicitProxyForm(target=<redacted>)"
    }

    data class ConnectAuthority(
        val authority: String,
    ) : ManagementIngressRequest {
        override fun toString(): String = "ConnectAuthority(authority=<redacted>)"
    }

    data object MalformedTarget : ManagementIngressRequest
}

object CloudflareManagementIngressPolicy {
    fun evaluate(request: ManagementIngressRequest): CloudflareIngressDecision {
        val shouldForward =
            when (request) {
                is ManagementIngressRequest.OriginForm ->
                    ManagementAccessPolicy.evaluate(request.method, request.path).isManagementRequest
                is ManagementIngressRequest.ExplicitProxyForm,
                is ManagementIngressRequest.ConnectAuthority,
                ManagementIngressRequest.MalformedTarget,
                -> false
            }

        return if (shouldForward) {
            CloudflareIngressDecision.Forward
        } else {
            CloudflareIngressDecision.Reject
        }
    }
}

private const val HEALTH_PATH = "/health"
private const val API_PREFIX = "/api/"

private fun String.pathComponent(): String {
    val queryStart = indexOf('?').takeUnless { it == -1 } ?: length
    val fragmentStart = indexOf('#').takeUnless { it == -1 } ?: length
    return substring(0, minOf(queryStart, fragmentStart))
}

private val HIGH_IMPACT_ENDPOINTS =
    setOf(
        HttpMethod.Post to "/api/cloudflare/start",
        HttpMethod.Post to "/api/cloudflare/stop",
        HttpMethod.Post to "/api/rotate/mobile-data",
        HttpMethod.Post to "/api/rotate/airplane-mode",
        HttpMethod.Post to "/api/service/stop",
    )
