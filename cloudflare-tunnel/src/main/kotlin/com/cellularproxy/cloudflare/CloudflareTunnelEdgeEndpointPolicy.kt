package com.cellularproxy.cloudflare

data class CloudflareTunnelEdgeEndpoint(
    val host: String,
    val port: Int,
)

object CloudflareTunnelEdgeEndpointPolicy {
    fun resolve(credentials: CloudflareTunnelCredentials): List<CloudflareTunnelEdgeEndpoint> {
        val explicitEndpoint = credentials.endpoint?.trim()
        return if (explicitEndpoint.isNullOrEmpty()) {
            DEFAULT_EDGE_ENDPOINTS
        } else {
            listOf(CloudflareTunnelEdgeEndpoint(explicitEndpoint, CLOUDFLARE_TUNNEL_EDGE_PORT))
        }
    }
}

private val DEFAULT_EDGE_ENDPOINTS =
    listOf(
        CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", CLOUDFLARE_TUNNEL_EDGE_PORT),
        CloudflareTunnelEdgeEndpoint("region2.v2.argotunnel.com", CLOUDFLARE_TUNNEL_EDGE_PORT),
    )

private const val CLOUDFLARE_TUNNEL_EDGE_PORT = 7844
