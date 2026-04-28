package com.cellularproxy.cloudflare

data class CloudflareTunnelEdgeEndpoint(
    val host: String,
    val port: Int,
)

object CloudflareTunnelEdgeEndpointPolicy {
    fun resolve(credentials: CloudflareTunnelCredentials): List<CloudflareTunnelEdgeEndpoint> {
        val explicitEndpoint = credentials.endpoint
        return if (explicitEndpoint == null || explicitEndpoint.isBlank()) {
            DEFAULT_EDGE_ENDPOINTS
        } else if (!explicitEndpoint.isValidExplicitEndpointHost()) {
            emptyList()
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
private const val MAX_DNS_HOST_LENGTH = 253
private const val MAX_DNS_LABEL_LENGTH = 63

private fun String.isValidExplicitEndpointHost(): Boolean {
    if (length > MAX_DNS_HOST_LENGTH || isEmpty()) {
        return false
    }

    return split(".").all(String::isValidDnsLabel)
}

private fun String.isValidDnsLabel(): Boolean {
    if (isEmpty() || length > MAX_DNS_LABEL_LENGTH || first() == '-' || last() == '-') {
        return false
    }

    return all { character ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-'
    }
}
