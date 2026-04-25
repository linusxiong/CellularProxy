package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.management.ManagementAccessPolicy

enum class ManagementApiOperation {
    Health,
    Status,
    Networks,
    PublicIp,
    CloudflareStatus,
    CloudflareStart,
    CloudflareStop,
    RotateMobileData,
    RotateAirplaneMode,
    ServiceStop,
}

sealed interface ManagementApiRouteDecision {
    data class Accepted(
        val operation: ManagementApiOperation,
        val requiresAuditLog: Boolean,
    ) : ManagementApiRouteDecision

    data class Rejected(
        val reason: ManagementApiRouteRejectionReason,
    ) : ManagementApiRouteDecision
}

enum class ManagementApiRouteRejectionReason {
    UnknownEndpoint,
    UnsupportedMethod,
    QueryUnsupported,
}

object ManagementApiRouter {
    fun route(request: ParsedProxyRequest.Management): ManagementApiRouteDecision {
        if ('?' in request.originTarget) {
            return ManagementApiRouteDecision.Rejected(ManagementApiRouteRejectionReason.QueryUnsupported)
        }

        val endpoint = ENDPOINTS[request.originTarget]
            ?: return ManagementApiRouteDecision.Rejected(ManagementApiRouteRejectionReason.UnknownEndpoint)

        if (endpoint.method != request.method) {
            return ManagementApiRouteDecision.Rejected(ManagementApiRouteRejectionReason.UnsupportedMethod)
        }

        val accessDecision = ManagementAccessPolicy.evaluate(request.method, request.originTarget)
        return ManagementApiRouteDecision.Accepted(
            operation = endpoint.operation,
            requiresAuditLog = accessDecision.requiresAuditLog,
        )
    }
}

private data class ManagementApiEndpoint(
    val method: HttpMethod,
    val operation: ManagementApiOperation,
)

private val ENDPOINTS = mapOf(
    "/health" to ManagementApiEndpoint(HttpMethod.Get, ManagementApiOperation.Health),
    "/api/status" to ManagementApiEndpoint(HttpMethod.Get, ManagementApiOperation.Status),
    "/api/networks" to ManagementApiEndpoint(HttpMethod.Get, ManagementApiOperation.Networks),
    "/api/ip" to ManagementApiEndpoint(HttpMethod.Get, ManagementApiOperation.PublicIp),
    "/api/cloudflare/status" to ManagementApiEndpoint(HttpMethod.Get, ManagementApiOperation.CloudflareStatus),
    "/api/cloudflare/start" to ManagementApiEndpoint(
        method = HttpMethod.Post,
        operation = ManagementApiOperation.CloudflareStart,
    ),
    "/api/cloudflare/stop" to ManagementApiEndpoint(
        method = HttpMethod.Post,
        operation = ManagementApiOperation.CloudflareStop,
    ),
    "/api/rotate/mobile-data" to ManagementApiEndpoint(
        method = HttpMethod.Post,
        operation = ManagementApiOperation.RotateMobileData,
    ),
    "/api/rotate/airplane-mode" to ManagementApiEndpoint(
        method = HttpMethod.Post,
        operation = ManagementApiOperation.RotateAirplaneMode,
    ),
    "/api/service/stop" to ManagementApiEndpoint(
        method = HttpMethod.Post,
        operation = ManagementApiOperation.ServiceStop,
    ),
)
