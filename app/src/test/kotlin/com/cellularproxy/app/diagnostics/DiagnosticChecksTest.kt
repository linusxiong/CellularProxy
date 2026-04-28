package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.root.RootAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticChecksTest {
    @Test
    fun `root availability check passes only when enabled root is available`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "root-disabled",
                details = "Root operations are disabled",
            ),
            DiagnosticChecks
                .rootAvailability(
                    rootOperationsEnabled = { false },
                    rootAvailability = { RootAvailabilityStatus.Available },
                ).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Root is available",
            ),
            DiagnosticChecks
                .rootAvailability(
                    rootOperationsEnabled = { true },
                    rootAvailability = { RootAvailabilityStatus.Available },
                ).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "root-unavailable",
                details = "Root is unavailable",
            ),
            DiagnosticChecks
                .rootAvailability(
                    rootOperationsEnabled = { true },
                    rootAvailability = { RootAvailabilityStatus.Unavailable },
                ).run(),
        )
    }

    @Test
    fun `selected route check evaluates automatic and explicit route availability`() {
        val networks =
            listOf(
                NetworkDescriptor(
                    id = "wifi-1",
                    category = NetworkCategory.WiFi,
                    displayName = "Office Wi-Fi",
                    isAvailable = true,
                ),
            )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Automatic route can use 1 available network",
            ),
            DiagnosticChecks
                .selectedRoute(
                    routeTarget = { RouteTarget.Automatic },
                    observedNetworks = { networks },
                ).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "route-unavailable",
                details = "No available Cellular network",
            ),
            DiagnosticChecks
                .selectedRoute(
                    routeTarget = { RouteTarget.Cellular },
                    observedNetworks = { networks },
                ).run(),
        )
    }

    @Test
    fun `public ip check fails when no route-bound probe result is available`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Public IP 203.0.113.10",
            ),
            DiagnosticChecks
                .publicIp(probeResult = { PublicIpDiagnosticsProbeResult.Observed("203.0.113.10") })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "public-ip-unavailable",
                details = "Public IP probe returned no address",
            ),
            DiagnosticChecks
                .publicIp(probeResult = { PublicIpDiagnosticsProbeResult.Unavailable })
                .run(),
        )
    }

    @Test
    fun `cloudflare tunnel check maps disabled connected degraded and failed states`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "cloudflare-disabled",
                details = "Cloudflare tunnel is disabled",
            ),
            DiagnosticChecks.cloudflareTunnel(status = { CloudflareTunnelStatus.disabled() }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Cloudflare tunnel is connected",
            ),
            DiagnosticChecks.cloudflareTunnel(status = { CloudflareTunnelStatus.connected() }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "cloudflare-degraded",
                details = "Cloudflare tunnel is degraded",
            ),
            DiagnosticChecks.cloudflareTunnel(status = { CloudflareTunnelStatus.degraded() }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "cloudflare-failed",
                details = "Cloudflare tunnel failed",
            ),
            DiagnosticChecks.cloudflareTunnel(status = { CloudflareTunnelStatus.failed("edge rejected secret-token") }).run(),
        )
    }

    @Test
    fun `proxy bind check maps bound warning and startup failure states`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Proxy listening on 127.0.0.1:8080",
            ),
            DiagnosticChecks
                .proxyBind(
                    status = {
                        ProxyServiceStatus.running(
                            listenHost = "127.0.0.1",
                            listenPort = 8080,
                            configuredRoute = RouteTarget.Automatic,
                            boundRoute = null,
                            publicIp = null,
                            hasHighSecurityRisk = false,
                        )
                    },
                ).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "proxy-bind-not-ready",
                details = "Proxy is stopped and not bound",
            ),
            DiagnosticChecks.proxyBind(status = { ProxyServiceStatus.stopped() }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "proxy-bind-not-ready",
                details = "Proxy is starting and not bound",
            ),
            DiagnosticChecks.proxyBind(status = { ProxyServiceStatus(state = ProxyServiceState.Starting) }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "proxy-bind-failed",
                details = "Proxy bind failed: PortAlreadyInUse",
            ),
            DiagnosticChecks.proxyBind(status = { ProxyServiceStatus.failed(ProxyStartupError.PortAlreadyInUse) }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "proxy-startup-blocked",
                details = "Proxy startup blocked before bind: MissingManagementApiToken",
            ),
            DiagnosticChecks
                .proxyBind(status = { ProxyServiceStatus.failed(ProxyStartupError.MissingManagementApiToken) })
                .run(),
        )
    }

    @Test
    fun `local management api check maps authenticated unavailable unauthorized and error probe results`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Local management API authenticated",
            ),
            DiagnosticChecks
                .localManagementApi(probeResult = { LocalManagementApiProbeResult.Authenticated })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "local-management-api-unavailable",
                details = "Local management API unavailable",
            ),
            DiagnosticChecks
                .localManagementApi(probeResult = { LocalManagementApiProbeResult.Unavailable })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "local-management-api-unauthorized",
                details = "Local management API rejected diagnostics authentication",
            ),
            DiagnosticChecks
                .localManagementApi(probeResult = { LocalManagementApiProbeResult.Unauthorized })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "local-management-api-error",
                details = "Local management API probe failed",
            ),
            DiagnosticChecks
                .localManagementApi(probeResult = { LocalManagementApiProbeResult.Error })
                .run(),
        )
    }

    @Test
    fun `cloudflare management api check maps explicit remote probe outcomes`() {
        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "cloudflare-management-api-not-configured",
                details = "Cloudflare management API check is not configured",
            ),
            DiagnosticChecks
                .cloudflareManagementApi(probeResult = { CloudflareManagementApiProbeResult.NotConfigured })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Passed,
                details = "Cloudflare management API authenticated",
            ),
            DiagnosticChecks
                .cloudflareManagementApi(probeResult = { CloudflareManagementApiProbeResult.Authenticated })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "cloudflare-management-api-unavailable",
                details = "Cloudflare management API unavailable",
            ),
            DiagnosticChecks
                .cloudflareManagementApi(probeResult = { CloudflareManagementApiProbeResult.Unavailable })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "cloudflare-management-api-unauthorized",
                details = "Cloudflare management API rejected diagnostics authentication",
            ),
            DiagnosticChecks
                .cloudflareManagementApi(probeResult = { CloudflareManagementApiProbeResult.Unauthorized })
                .run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "cloudflare-management-api-error",
                details = "Cloudflare management API probe failed",
            ),
            DiagnosticChecks
                .cloudflareManagementApi(probeResult = { CloudflareManagementApiProbeResult.Error })
                .run(),
        )
    }
}
