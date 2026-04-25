package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagementApiReadOnlyResponsesTest {
    @Test
    fun `health response exposes only minimal service health`() {
        val response = ManagementApiReadOnlyResponses.health(
            ProxyServiceStatus.running(
                listenHost = "0.0.0.0",
                listenPort = 8080,
                configuredRoute = RouteTarget.Automatic,
                boundRoute = null,
                publicIp = "198.51.100.10",
                hasHighSecurityRisk = true,
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true,"serviceState":"running"}""", response.body)
        assertFalse(response.body.contains("0.0.0.0"))
        assertFalse(response.body.contains("198.51.100.10"))
    }

    @Test
    fun `status response renders dashboard fields and redacts cloudflare failure reason`() {
        val response = ManagementApiReadOnlyResponses.status(
            status = ProxyServiceStatus.running(
                listenHost = "0.0.0.0",
                listenPort = 8080,
                configuredRoute = RouteTarget.Cellular,
                boundRoute = NetworkDescriptor(
                    id = "cell-1",
                    category = NetworkCategory.Cellular,
                    displayName = "Carrier LTE",
                    isAvailable = true,
                ),
                publicIp = "198.51.100.23",
                hasHighSecurityRisk = true,
                cloudflare = CloudflareTunnelStatus.failed(
                    "edge rejected Authorization: Bearer raw-token at https://edge.example.test/cdn-cgi?token=raw-token",
                ),
                rootAvailability = RootAvailabilityStatus.Available,
                metrics = ProxyTrafficMetrics(
                    activeConnections = 2,
                    totalConnections = 5,
                    rejectedConnections = 1,
                    bytesReceived = 42,
                    bytesSent = 99,
                ),
            ),
            secrets = LogRedactionSecrets(managementApiToken = "raw-token"),
        )

        assertEquals(200, response.statusCode)
        assertEquals(
            "{" +
                """"service":{"state":"running","listenHost":"0.0.0.0","listenPort":8080,"configuredRoute":"cellular","boundRoute":{"id":"cell-1","category":"cellular","displayName":"Carrier LTE","available":true},"publicIp":"198.51.100.23","highSecurityRisk":true,"startupError":null},""" +
                """"metrics":{"activeConnections":2,"totalConnections":5,"rejectedConnections":1,"bytesReceived":42,"bytesSent":99},""" +
                """"cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"edge rejected Authorization: [REDACTED]"},""" +
                """"root":{"availability":"available"}""" +
                "}",
            response.body,
        )
        assertFalse(response.body.contains("raw-token"))
    }

    @Test
    fun `status response includes failed startup error`() {
        val response = ManagementApiReadOnlyResponses.status(
            ProxyServiceStatus.failed(startupError = ProxyStartupError.PortAlreadyInUse),
        )

        assertEquals(
            "{" +
                """"service":{"state":"failed","listenHost":null,"listenPort":null,"configuredRoute":"automatic","boundRoute":null,"publicIp":null,"highSecurityRisk":false,"startupError":"port_already_in_use"},""" +
                """"metrics":{"activeConnections":0,"totalConnections":0,"rejectedConnections":0,"bytesReceived":0,"bytesSent":0},""" +
                """"cloudflare":{"state":"disabled","remoteManagementAvailable":false,"failureReason":null},""" +
                """"root":{"availability":"unknown"}""" +
                "}",
            response.body,
        )
    }

    @Test
    fun `networks response preserves order and renders routeable network descriptors`() {
        val response = ManagementApiReadOnlyResponses.networks(
            listOf(
                NetworkDescriptor("wifi-1", NetworkCategory.WiFi, "Office Wi-Fi", true),
                NetworkDescriptor("vpn-1", NetworkCategory.Vpn, "Tailscale", false),
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals(
            """{"networks":[{"id":"wifi-1","category":"wifi","displayName":"Office Wi-Fi","available":true},{"id":"vpn-1","category":"vpn","displayName":"Tailscale","available":false}]}""",
            response.body,
        )
    }

    @Test
    fun `public ip response renders null and observed values`() {
        assertEquals("""{"publicIp":null}""", ManagementApiReadOnlyResponses.publicIp(null).body)
        assertEquals("""{"publicIp":"203.0.113.7"}""", ManagementApiReadOnlyResponses.publicIp("203.0.113.7").body)
    }

    @Test
    fun `cloudflare status response redacts configured secrets from free-form failure reasons`() {
        val response = ManagementApiReadOnlyResponses.cloudflareStatus(
            status = CloudflareTunnelStatus.failed("failed with tunnel-secret in response"),
            secrets = LogRedactionSecrets(cloudflareTunnelToken = "tunnel-secret"),
        )

        assertEquals(
            """{"cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"failed with [REDACTED] in response"}}""",
            response.body,
        )
        assertFalse(response.body.contains("tunnel-secret"))
    }
}
