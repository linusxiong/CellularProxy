package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RootConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalManagementApiStatusReaderTest {
    @Test
    fun `loads running proxy status from authenticated management status response`() {
        val requests = mutableListOf<LocalManagementApiActionRequest>()
        val reader =
            LocalManagementApiStatusReader { request ->
                requests += request
                LocalManagementApiStatusResponse(
                    statusCode = 200,
                    body =
                        "{" +
                            """"service":{"state":"running","listenHost":"0.0.0.0","listenPort":8081,"configuredRoute":"cellular","boundRoute":{"id":"cell-1","category":"cellular","displayName":"Carrier LTE","available":true},"publicIp":"198.51.100.23","highSecurityRisk":true,"startupError":null},""" +
                            """"metrics":{"activeConnections":2,"totalConnections":5,"rejectedConnections":1,"bytesReceived":42,"bytesSent":99},""" +
                            """"cloudflare":{"state":"connected","remoteManagementAvailable":true,"failureReason":null},""" +
                            """"root":{"operationsEnabled":true,"availability":"available"},""" +
                            """"rotation":{"state":"idle","operation":null,"oldPublicIp":null,"newPublicIp":null,"publicIpChanged":null,"failureReason":null}""" +
                            "}",
                )
            }

        val status =
            reader.load(
                config = config(),
                sensitiveConfig = sensitiveConfig(),
            )

        assertEquals(
            listOf(
                LocalManagementApiActionRequest(
                    method = "GET",
                    url = "http://127.0.0.1:8081/api/status",
                    bearerToken = "management-token",
                ),
            ),
            requests,
        )
        requireNotNull(status)
        assertEquals(ProxyServiceState.Running, status.state)
        assertEquals("0.0.0.0", status.listenHost)
        assertEquals(8081, status.listenPort)
        assertEquals(RouteTarget.Cellular, status.configuredRoute)
        assertEquals("198.51.100.23", status.publicIp)
        assertEquals(true, status.hasHighSecurityRisk)
        assertEquals(2, status.metrics.activeConnections)
        assertEquals(5, status.metrics.totalConnections)
        assertEquals(NetworkCategory.Cellular, status.boundRoute?.category)
        assertEquals("Carrier LTE", status.boundRoute?.displayName)
        assertEquals(CloudflareTunnelState.Connected, status.cloudflare.state)
        assertEquals(RootAvailabilityStatus.Available, status.rootAvailability)
    }

    @Test
    fun `loads runtime snapshot with current rotation status from management status response`() {
        val reader =
            LocalManagementApiStatusReader {
                LocalManagementApiStatusResponse(
                    statusCode = 200,
                    body =
                        "{" +
                            """"service":{"state":"running","listenHost":"0.0.0.0","listenPort":8081,"configuredRoute":"cellular","boundRoute":null,"publicIp":"198.51.100.23","highSecurityRisk":false,"startupError":null},""" +
                            """"metrics":{"activeConnections":0,"totalConnections":0,"rejectedConnections":0,"bytesReceived":0,"bytesSent":0},""" +
                            """"cloudflare":{"state":"connected","remoteManagementAvailable":true,"failureReason":null},""" +
                            """"root":{"operationsEnabled":true,"availability":"available"},""" +
                            """"rotation":{"state":"waiting_for_network_return","operation":"mobile_data","oldPublicIp":"198.51.100.23","newPublicIp":null,"publicIpChanged":null,"failureReason":null}""" +
                            "}",
                )
            }

        val snapshot = reader.loadSnapshot(config(), sensitiveConfig())

        requireNotNull(snapshot)
        assertEquals(ProxyServiceState.Running, snapshot.proxyStatus.state)
        assertEquals(RotationState.WaitingForNetworkReturn, snapshot.rotationStatus.state)
        assertEquals(RotationOperation.MobileData, snapshot.rotationStatus.operation)
        assertEquals("198.51.100.23", snapshot.rotationStatus.oldPublicIp)
    }

    @Test
    fun `returns null when management status response is unavailable or invalid`() {
        val unauthorizedReader =
            LocalManagementApiStatusReader {
                LocalManagementApiStatusResponse(statusCode = 401, body = """{"error":"unauthorized"}""")
            }
        val invalidReader =
            LocalManagementApiStatusReader {
                LocalManagementApiStatusResponse(statusCode = 200, body = """{"service":{}}""")
            }

        assertNull(unauthorizedReader.load(config(), sensitiveConfig()))
        assertNull(invalidReader.load(config(), sensitiveConfig()))
    }

    @Test
    fun `returns null when management status transport fails`() {
        val reader =
            LocalManagementApiStatusReader {
                error("connection refused")
            }

        assertNull(reader.load(config(), sensitiveConfig()))
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
)

private fun config(): AppConfig = AppConfig(
    proxy = ProxyConfig(listenHost = "0.0.0.0", listenPort = 8081),
    network = NetworkConfig(defaultRoutePolicy = RouteTarget.Cellular),
    rotation = RotationConfig(),
    cloudflare = CloudflareConfig(),
    root = RootConfig(operationsEnabled = true),
)
