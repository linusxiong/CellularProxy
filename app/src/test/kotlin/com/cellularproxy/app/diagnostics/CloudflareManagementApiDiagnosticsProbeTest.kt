package com.cellularproxy.app.diagnostics

import com.cellularproxy.cloudflare.CloudflareLocalManagementRequest
import com.cellularproxy.cloudflare.CloudflareTunnelResponse
import com.cellularproxy.shared.management.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloudflareManagementApiDiagnosticsProbeTest {
    @Test
    fun `probe authenticates status request through management-only cloudflare ingress`() {
        val requests = mutableListOf<CloudflareLocalManagementRequest>()
        val probe =
            CloudflareManagementApiDiagnosticsProbe(
                managementApiToken = { "management-token" },
                localManagementHandler = { request ->
                    requests += request
                    CloudflareTunnelResponse.json(statusCode = 200, body = """{"state":"running"}""")
                },
            )

        val result = probe.run()

        assertEquals(CloudflareManagementApiProbeResult.Authenticated, result)
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals("/api/status", requests.single().originTarget)
        assertEquals(listOf("Bearer management-token"), requests.single().headers["Authorization"])
    }

    @Test
    fun `probe reports unauthorized without exposing the management token in diagnostics text`() {
        val probe =
            CloudflareManagementApiDiagnosticsProbe(
                managementApiToken = { "wrong-token" },
                localManagementHandler = {
                    CloudflareTunnelResponse.json(statusCode = 401, body = "Unauthorized wrong-token")
                },
            )

        val result = probe.run()

        assertEquals(CloudflareManagementApiProbeResult.Unauthorized, result)
        assertFalse(probe.toString().contains("wrong-token"))
    }

    @Test
    fun `probe reports unavailable and generic errors without throwing`() {
        assertEquals(
            CloudflareManagementApiProbeResult.Unavailable,
            CloudflareManagementApiDiagnosticsProbe(
                managementApiToken = { "management-token" },
                localManagementHandler = {
                    CloudflareTunnelResponse.json(
                        statusCode = 503,
                        body = """{"error":"management_unavailable"}""",
                    )
                },
            ).run(),
        )

        assertEquals(
            CloudflareManagementApiProbeResult.Error,
            CloudflareManagementApiDiagnosticsProbe(
                managementApiToken = { "management-token" },
                localManagementHandler = { error("edge failure with management-token") },
            ).run(),
        )
    }

    @Test
    fun `probe is not configured when token is blank`() {
        val calls = mutableListOf<CloudflareLocalManagementRequest>()
        val probe =
            CloudflareManagementApiDiagnosticsProbe(
                managementApiToken = { " " },
                localManagementHandler = { request ->
                    calls += request
                    CloudflareTunnelResponse.empty(statusCode = 500)
                },
            )

        assertEquals(CloudflareManagementApiProbeResult.NotConfigured, probe.run())
        assertEquals(emptyList(), calls)
    }
}
