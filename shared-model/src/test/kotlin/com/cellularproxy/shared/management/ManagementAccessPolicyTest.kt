package com.cellularproxy.shared.management

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagementAccessPolicyTest {
    @Test
    fun `GET health is public and does not require audit logging`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/health")

        assertTrue(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `non-GET health is not classified as a management request`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Post, "/health")

        assertFalse(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `all api paths require the management token`() {
        val status = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/api/status")
        val serviceStop = ManagementAccessPolicy.evaluate(HttpMethod.Post, "/api/service/stop")

        assertTrue(status.isManagementRequest)
        assertTrue(status.requiresToken)
        assertTrue(serviceStop.isManagementRequest)
        assertTrue(serviceStop.requiresToken)
    }

    @Test
    fun `high-impact api endpoints require audit logging`() {
        val endpoints = listOf(
            HttpMethod.Post to "/api/cloudflare/start",
            HttpMethod.Post to "/api/cloudflare/stop",
            HttpMethod.Post to "/api/rotate/mobile-data",
            HttpMethod.Post to "/api/rotate/airplane-mode",
            HttpMethod.Post to "/api/service/stop",
        )

        endpoints.forEach { (method, path) ->
            val decision = ManagementAccessPolicy.evaluate(method, path)

            assertTrue(decision.requiresToken, "Expected $method $path to require a token")
            assertTrue(decision.requiresAuditLog, "Expected $method $path to require audit logging")
        }
    }

    @Test
    fun `high-impact api endpoints still require audit logging when origin target has a query`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Post, "/api/service/stop?reason=user-requested")

        assertTrue(decision.isManagementRequest)
        assertTrue(decision.requiresToken)
        assertTrue(decision.requiresAuditLog)
    }

    @Test
    fun `health endpoint classification uses the path component of an origin target`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/health?probe=cloudflare")

        assertTrue(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `read-only api endpoints do not require audit logging`() {
        val endpoints = listOf(
            HttpMethod.Get to "/api/status",
            HttpMethod.Get to "/api/networks",
            HttpMethod.Get to "/api/ip",
            HttpMethod.Get to "/api/cloudflare/status",
        )

        endpoints.forEach { (method, path) ->
            val decision = ManagementAccessPolicy.evaluate(method, path)

            assertTrue(decision.requiresToken, "Expected $method $path to require a token")
            assertFalse(decision.requiresAuditLog, "Expected $method $path not to require audit logging")
        }
    }

    @Test
    fun `non-management paths are not classified as management requests`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/proxy")

        assertFalse(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `bare api path is not treated as an api prefix`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/api")

        assertFalse(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `similar api prefixes are not treated as management api paths`() {
        val decision = ManagementAccessPolicy.evaluate(HttpMethod.Get, "/apiary/status")

        assertFalse(decision.isManagementRequest)
        assertFalse(decision.requiresToken)
        assertFalse(decision.requiresAuditLog)
    }

    @Test
    fun `cloudflare ingress forwards public health and api paths`() {
        assertEquals(
            CloudflareIngressDecision.Forward,
            CloudflareManagementIngressPolicy.evaluate(ManagementIngressRequest.OriginForm(HttpMethod.Get, "/health")),
        )
        assertEquals(
            CloudflareIngressDecision.Forward,
            CloudflareManagementIngressPolicy.evaluate(ManagementIngressRequest.OriginForm(HttpMethod.Get, "/api/status")),
        )
    }

    @Test
    fun `cloudflare ingress forwards origin-form targets using the path component`() {
        assertEquals(
            CloudflareIngressDecision.Forward,
            CloudflareManagementIngressPolicy.evaluate(ManagementIngressRequest.OriginForm(HttpMethod.Get, "/health?probe=1")),
        )
        assertEquals(
            CloudflareIngressDecision.Forward,
            CloudflareManagementIngressPolicy.evaluate(
                ManagementIngressRequest.OriginForm(HttpMethod.Post, "/api/service/stop?reason=user-requested"),
            ),
        )
    }

    @Test
    fun `cloudflare ingress rejects non-GET health bare api and similar prefixes`() {
        val rejected = listOf(
            ManagementIngressRequest.OriginForm(HttpMethod.Post, "/health"),
            ManagementIngressRequest.OriginForm(HttpMethod.Get, "/api"),
            ManagementIngressRequest.OriginForm(HttpMethod.Get, "/apiary/status"),
            ManagementIngressRequest.OriginForm(HttpMethod.Get, "/"),
        )

        rejected.forEach { request ->
            assertEquals(CloudflareIngressDecision.Reject, CloudflareManagementIngressPolicy.evaluate(request))
        }
    }

    @Test
    fun `cloudflare ingress rejects explicit proxy request forms`() {
        assertEquals(
            CloudflareIngressDecision.Reject,
            CloudflareManagementIngressPolicy.evaluate(
                ManagementIngressRequest.ExplicitProxyForm("http://example.com/api/status"),
            ),
        )
        assertEquals(
            CloudflareIngressDecision.Reject,
            CloudflareManagementIngressPolicy.evaluate(
                ManagementIngressRequest.ConnectAuthority("example.com:443"),
            ),
        )
    }
}
