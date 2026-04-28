package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.root.RootAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsSuiteControllerFactoryTest {
    @Test
    fun `create wires every diagnostics check to current runtime providers`() {
        val wifi =
            NetworkDescriptor(
                id = "wifi-1",
                category = NetworkCategory.WiFi,
                displayName = "Office Wi-Fi",
                isAvailable = true,
            )
        val status =
            ProxyServiceStatus.running(
                listenHost = "127.0.0.1",
                listenPort = 8080,
                configuredRoute = RouteTarget.WiFi,
                boundRoute = wifi,
                publicIp = "203.0.113.8",
                hasHighSecurityRisk = false,
                cloudflare = CloudflareTunnelStatus.connected(),
                rootAvailability = RootAvailabilityStatus.Available,
            )
        val controller =
            DiagnosticsSuiteControllerFactory.create(
                config = {
                    AppConfig.default().copy(
                        network = NetworkConfig(defaultRoutePolicy = RouteTarget.WiFi),
                        root = AppConfig.default().root.copy(operationsEnabled = true),
                    )
                },
                proxyStatus = { status },
                observedNetworks = { listOf(wifi) },
                publicIpProbeResult = { PublicIpDiagnosticsProbeResult.Observed("203.0.113.9") },
                localManagementApiProbeResult = { LocalManagementApiProbeResult.Authenticated },
                cloudflareManagementApiProbeResult = { CloudflareManagementApiProbeResult.Authenticated },
                nanoTime = { 0L },
            )

        val results =
            DiagnosticCheckType.entries.associateWith { type ->
                controller.run(type)
            }

        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.RootAvailability).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.SelectedRoute).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.PublicIp).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.ProxyBind).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.LocalManagementApi).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.CloudflareTunnel).status)
        assertEquals(DiagnosticResultStatus.Passed, results.getValue(DiagnosticCheckType.CloudflareManagementApi).status)
        assertEquals("Public IP 203.0.113.9", results.getValue(DiagnosticCheckType.PublicIp).details)
    }
}
