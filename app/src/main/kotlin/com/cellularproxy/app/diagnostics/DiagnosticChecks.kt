package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.root.RootAvailabilityStatus
import java.net.Inet6Address
import java.net.InetAddress

object DiagnosticChecks {
    fun rootAvailability(
        rootOperationsEnabled: () -> Boolean,
        rootAvailability: () -> RootAvailabilityStatus,
    ): DiagnosticCheck = DiagnosticCheck {
        if (!rootOperationsEnabled()) {
            return@DiagnosticCheck DiagnosticCheckResult(
                status = DiagnosticResultStatus.Warning,
                errorCategory = "root-disabled",
                details = "Root operations are disabled",
            )
        }

        when (rootAvailability()) {
            RootAvailabilityStatus.Available ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Root is available",
                )
            RootAvailabilityStatus.Unavailable ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "root-unavailable",
                    details = "Root is unavailable",
                )
            RootAvailabilityStatus.Unknown ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "root-unknown",
                    details = "Root availability is unknown",
                )
        }
    }

    fun selectedRoute(
        routeTarget: () -> RouteTarget,
        observedNetworks: () -> List<NetworkDescriptor>,
    ): DiagnosticCheck = DiagnosticCheck {
        val target = routeTarget()
        val availableNetworks = observedNetworks().filter(NetworkDescriptor::isAvailable)
        val matchingNetworks = availableNetworks.filter { it.category == target.networkCategory }

        when {
            target == RouteTarget.Automatic && availableNetworks.isNotEmpty() ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details =
                        "Automatic route can use ${availableNetworks.size} available network".pluralize(
                            availableNetworks.size,
                        ),
                )
            target == RouteTarget.Automatic ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "route-unavailable",
                    details = "No available networks",
                )
            matchingNetworks.isNotEmpty() ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details =
                        "${target.label} route can use ${matchingNetworks.size} available network".pluralize(
                            matchingNetworks.size,
                        ),
                )
            else ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "route-unavailable",
                    details = "No available ${target.label} network",
                )
        }
    }

    fun publicIp(probeResult: () -> PublicIpDiagnosticsProbeResult): DiagnosticCheck = DiagnosticCheck {
        when (val result = probeResult()) {
            is PublicIpDiagnosticsProbeResult.Observed ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Public IP ${result.address}",
                )
            PublicIpDiagnosticsProbeResult.Unavailable ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "public-ip-unavailable",
                    details = "Public IP probe returned no address",
                )
        }
    }

    fun cloudflareTunnel(status: () -> CloudflareTunnelStatus): DiagnosticCheck = DiagnosticCheck {
        when (status().state) {
            CloudflareTunnelState.Disabled ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "cloudflare-disabled",
                    details = "Cloudflare tunnel is disabled",
                )
            CloudflareTunnelState.Starting ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "cloudflare-starting",
                    details = "Cloudflare tunnel is starting",
                )
            CloudflareTunnelState.Connected ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Cloudflare tunnel is connected",
                )
            CloudflareTunnelState.Degraded ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "cloudflare-degraded",
                    details = "Cloudflare tunnel is degraded",
                )
            CloudflareTunnelState.Stopped ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "cloudflare-stopped",
                    details = "Cloudflare tunnel is stopped",
                )
            CloudflareTunnelState.Failed ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "cloudflare-failed",
                    details = "Cloudflare tunnel failed",
                )
        }
    }

    fun proxyBind(status: () -> ProxyServiceStatus): DiagnosticCheck = DiagnosticCheck {
        val proxyStatus = status()
        when (proxyStatus.state) {
            ProxyServiceState.Running ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Proxy listening on ${proxyStatus.listenHost}:${proxyStatus.listenPort}",
                )
            ProxyServiceState.Failed ->
                proxyStatus.startupError.toProxyBindFailureResult()
            ProxyServiceState.Starting,
            ProxyServiceState.Stopping,
            ProxyServiceState.Stopped,
            ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "proxy-bind-not-ready",
                    details = "Proxy is ${proxyStatus.state.name.lowercase()} and not bound",
                )
        }
    }

    fun localManagementApi(probeResult: () -> LocalManagementApiProbeResult): DiagnosticCheck = DiagnosticCheck {
        when (probeResult()) {
            LocalManagementApiProbeResult.Authenticated ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Local management API authenticated",
                )
            LocalManagementApiProbeResult.Unavailable ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "local-management-api-unavailable",
                    details = "Local management API unavailable",
                )
            LocalManagementApiProbeResult.Unauthorized ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "local-management-api-unauthorized",
                    details = "Local management API rejected diagnostics authentication",
                )
            LocalManagementApiProbeResult.Error ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "local-management-api-error",
                    details = "Local management API probe failed",
                )
        }
    }

    fun cloudflareManagementApi(probeResult: () -> CloudflareManagementApiProbeResult): DiagnosticCheck = DiagnosticCheck {
        when (probeResult()) {
            CloudflareManagementApiProbeResult.NotConfigured ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Warning,
                    errorCategory = "cloudflare-management-api-not-configured",
                    details = "Cloudflare management API check is not configured",
                )
            CloudflareManagementApiProbeResult.Authenticated ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Passed,
                    details = "Cloudflare management API authenticated",
                )
            CloudflareManagementApiProbeResult.Unavailable ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "cloudflare-management-api-unavailable",
                    details = "Cloudflare management API unavailable",
                )
            CloudflareManagementApiProbeResult.Unauthorized ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "cloudflare-management-api-unauthorized",
                    details = "Cloudflare management API rejected diagnostics authentication",
                )
            CloudflareManagementApiProbeResult.Error ->
                DiagnosticCheckResult(
                    status = DiagnosticResultStatus.Failed,
                    errorCategory = "cloudflare-management-api-error",
                    details = "Cloudflare management API probe failed",
                )
        }
    }
}

enum class LocalManagementApiProbeResult {
    Authenticated,
    Unavailable,
    Unauthorized,
    Error,
}

sealed interface PublicIpDiagnosticsProbeResult {
    data object Unavailable : PublicIpDiagnosticsProbeResult

    data class Observed(
        val address: String,
    ) : PublicIpDiagnosticsProbeResult {
        init {
            require(address.isNotBlank()) { "Observed public IP address must not be blank" }
            require(address.isIpLiteral()) { "Observed public IP address must be an IP literal" }
        }
    }
}

enum class CloudflareManagementApiProbeResult {
    NotConfigured,
    Authenticated,
    Unavailable,
    Unauthorized,
    Error,
}

private fun ProxyStartupError?.toProxyBindFailureResult(): DiagnosticCheckResult = if (this == ProxyStartupError.PortAlreadyInUse) {
    DiagnosticCheckResult(
        status = DiagnosticResultStatus.Failed,
        errorCategory = "proxy-bind-failed",
        details = "Proxy bind failed: $this",
    )
} else {
    DiagnosticCheckResult(
        status = DiagnosticResultStatus.Failed,
        errorCategory = "proxy-startup-blocked",
        details = "Proxy startup blocked before bind: $this",
    )
}

private val RouteTarget.networkCategory: NetworkCategory?
    get() =
        when (this) {
            RouteTarget.WiFi -> NetworkCategory.WiFi
            RouteTarget.Cellular -> NetworkCategory.Cellular
            RouteTarget.Vpn -> NetworkCategory.Vpn
            RouteTarget.Automatic -> null
        }

private val RouteTarget.label: String
    get() =
        when (this) {
            RouteTarget.WiFi -> "Wi-Fi"
            RouteTarget.Cellular -> "Cellular"
            RouteTarget.Vpn -> "VPN"
            RouteTarget.Automatic -> "Automatic"
        }

private fun String.pluralize(count: Int): String = if (count == 1) this else "${this}s"

private fun String.isIpLiteral(): Boolean = isIpv4Literal() || isIpv6Literal()

private fun String.isIpv4Literal(): Boolean {
    if (!IPV4_LITERAL_REGEX.matches(this)) {
        return false
    }
    return split(".").all { octet ->
        octet.toIntOrNull()?.let { value -> value in 0..255 } == true
    }
}

private fun String.isIpv6Literal(): Boolean {
    if (':' !in this || !IPV6_LITERAL_CHARS_REGEX.matches(this)) {
        return false
    }
    return runCatching { InetAddress.getByName(this) is Inet6Address }
        .getOrDefault(false)
}

private val IPV4_LITERAL_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val IPV6_LITERAL_CHARS_REGEX = Regex("""[0-9A-Fa-f:.]+""")
