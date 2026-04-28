package com.cellularproxy.cloudflare

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudflareTunnelEdgeEndpointPolicyTest {
    @Test
    fun `uses explicit token endpoint when present`() {
        val endpoints =
            CloudflareTunnelEdgeEndpointPolicy.resolve(
                credentials(endpoint = "custom-edge.example.com"),
            )

        assertEquals(listOf(CloudflareTunnelEdgeEndpoint("custom-edge.example.com", 7844)), endpoints)
    }

    @Test
    fun `falls back to documented Cloudflare edge regions when token has no endpoint`() {
        val endpoints =
            CloudflareTunnelEdgeEndpointPolicy.resolve(
                credentials(endpoint = null),
            )

        assertEquals(
            listOf(
                CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", 7844),
                CloudflareTunnelEdgeEndpoint("region2.v2.argotunnel.com", 7844),
            ),
            endpoints,
        )
    }

    @Test
    fun `ignores blank explicit endpoint and uses default regions`() {
        val endpoints =
            CloudflareTunnelEdgeEndpointPolicy.resolve(
                credentials(endpoint = "   "),
            )

        assertEquals(
            listOf(
                CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", 7844),
                CloudflareTunnelEdgeEndpoint("region2.v2.argotunnel.com", 7844),
            ),
            endpoints,
        )
    }

    @Test
    fun `does not expose credentials in endpoint diagnostics`() {
        val endpoint = CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", 7844)

        assertEquals("CloudflareTunnelEdgeEndpoint(host=region1.v2.argotunnel.com, port=7844)", endpoint.toString())
    }

    private fun credentials(endpoint: String?): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        tunnelSecret = ByteArray(32) { index -> index.toByte() },
        endpoint = endpoint,
    )
}
