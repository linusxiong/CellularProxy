package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.HttpMethod
import com.cellularproxy.shared.management.ManagementAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagementApiRouterTest {
    @Test
    fun `routes public health without audit`() {
        val decision = ManagementApiRouter.route(managementRequest(HttpMethod.Get, "/health"))

        val accepted = assertIs<ManagementApiRouteDecision.Accepted>(decision)
        assertEquals(ManagementApiOperation.Health, accepted.operation)
        assertFalse(accepted.requiresAuditLog)
    }

    @Test
    fun `routes read-only api endpoints without audit`() {
        val endpoints = mapOf(
            "/api/status" to ManagementApiOperation.Status,
            "/api/networks" to ManagementApiOperation.Networks,
            "/api/ip" to ManagementApiOperation.PublicIp,
            "/api/cloudflare/status" to ManagementApiOperation.CloudflareStatus,
        )

        endpoints.forEach { (target, expectedOperation) ->
            val accepted = assertIs<ManagementApiRouteDecision.Accepted>(
                ManagementApiRouter.route(managementRequest(HttpMethod.Get, target)),
                "Expected $target to route",
            )
            assertEquals(expectedOperation, accepted.operation)
            assertFalse(accepted.requiresAuditLog)
        }
    }

    @Test
    fun `routes high-impact command endpoints with audit metadata`() {
        val endpoints = mapOf(
            "/api/cloudflare/start" to ManagementApiOperation.CloudflareStart,
            "/api/cloudflare/stop" to ManagementApiOperation.CloudflareStop,
            "/api/rotate/mobile-data" to ManagementApiOperation.RotateMobileData,
            "/api/rotate/airplane-mode" to ManagementApiOperation.RotateAirplaneMode,
            "/api/service/stop" to ManagementApiOperation.ServiceStop,
        )

        endpoints.forEach { (target, expectedOperation) ->
            val accepted = assertIs<ManagementApiRouteDecision.Accepted>(
                ManagementApiRouter.route(
                    managementRequest(
                        method = HttpMethod.Post,
                        originTarget = target,
                    ),
                ),
                "Expected $target to route",
            )
            assertEquals(expectedOperation, accepted.operation)
            assertTrue(accepted.requiresAuditLog)
        }
    }

    @Test
    fun `router audit metadata stays consistent with shared access policy`() {
        val routedEndpoints = listOf(
            HttpMethod.Get to "/health",
            HttpMethod.Get to "/api/status",
            HttpMethod.Get to "/api/networks",
            HttpMethod.Get to "/api/ip",
            HttpMethod.Get to "/api/cloudflare/status",
            HttpMethod.Post to "/api/cloudflare/start",
            HttpMethod.Post to "/api/cloudflare/stop",
            HttpMethod.Post to "/api/rotate/mobile-data",
            HttpMethod.Post to "/api/rotate/airplane-mode",
            HttpMethod.Post to "/api/service/stop",
        )

        routedEndpoints.forEach { (method, target) ->
            val accepted = assertIs<ManagementApiRouteDecision.Accepted>(
                ManagementApiRouter.route(managementRequest(method, target)),
                "Expected $target to route",
            )
            assertEquals(
                ManagementAccessPolicy.evaluate(method, target).requiresAuditLog,
                accepted.requiresAuditLog,
                "Expected $target audit metadata to match shared access policy",
            )
        }
    }

    @Test
    fun `rejects known endpoints with unsupported methods`() {
        val unsupportedMethodRequests = mapOf(
            managementRequest(HttpMethod.Post, "/api/status") to HttpMethod.Get,
            managementRequest(HttpMethod.Get, "/api/cloudflare/start") to HttpMethod.Post,
            managementRequest(HttpMethod.Get, "/api/service/stop") to HttpMethod.Post,
        )

        unsupportedMethodRequests.forEach { (request, expectedAllowedMethod) ->
            assertEquals(
                ManagementApiRouteDecision.Rejected(
                    ManagementApiRouteRejectionReason.UnsupportedMethod(expectedAllowedMethod),
                ),
                ManagementApiRouter.route(request),
                "Expected $request to reject unsupported method",
            )
        }
    }

    @Test
    fun `rejects unknown and broad-prefix endpoints`() {
        val unknownTargets = listOf(
            "/api",
            "/api/unknown",
            "/api/status/details",
            "/apiary/status",
        )

        unknownTargets.forEach { target ->
            assertEquals(
                ManagementApiRouteDecision.Rejected(ManagementApiRouteRejectionReason.UnknownEndpoint),
                ManagementApiRouter.route(managementRequest(HttpMethod.Get, target)),
                "Expected $target to be unknown",
            )
        }
    }

    @Test
    fun `rejects query-bearing targets instead of silently routing by path`() {
        val decision = ManagementApiRouter.route(managementRequest(HttpMethod.Get, "/api/status?verbose=true"))

        assertEquals(
            ManagementApiRouteDecision.Rejected(ManagementApiRouteRejectionReason.QueryUnsupported),
            decision,
        )
    }

    private fun managementRequest(
        method: HttpMethod,
        originTarget: String,
        requiresToken: Boolean = originTarget.startsWith("/api/"),
        requiresAuditLog: Boolean = false,
    ): ParsedProxyRequest.Management =
        ParsedProxyRequest.Management(
            method = method,
            originTarget = originTarget,
            requiresToken = requiresToken,
            requiresAuditLog = requiresAuditLog,
        )
}
