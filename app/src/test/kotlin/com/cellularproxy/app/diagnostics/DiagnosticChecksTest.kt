package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
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
            DiagnosticChecks.publicIp(publicIp = { "203.0.113.10" }).run(),
        )

        assertEquals(
            DiagnosticCheckResult(
                status = DiagnosticResultStatus.Failed,
                errorCategory = "public-ip-unavailable",
                details = "Public IP probe returned no address",
            ),
            DiagnosticChecks.publicIp(publicIp = { null }).run(),
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
}
