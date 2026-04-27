package com.cellularproxy.app.status

import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.root.RootAvailabilityStatus

data class DashboardStatusModel(
    val serviceState: DashboardServiceState,
    val listenEndpoint: String,
    val configuredRoute: DashboardRouteTarget,
    val boundRoute: DashboardBoundRoute?,
    val publicIp: String?,
    val activeConnections: Long,
    val totalConnections: Long,
    val rejectedConnections: Long,
    val bytesReceived: Long,
    val bytesSent: Long,
    val cloudflare: DashboardCloudflareStatus,
    val root: DashboardRootState,
    val startupError: ProxyStartupError?,
    val warnings: Set<DashboardWarning>,
) {
    companion object {
        fun from(
            config: AppConfig,
            status: ProxyServiceStatus,
        ): DashboardStatusModel {
            val cloudflare = status.cloudflare.toDashboardCloudflareStatus()
            return DashboardStatusModel(
                serviceState = status.state.toDashboardServiceState(),
                listenEndpoint = listenEndpoint(config, status),
                configuredRoute = status.configuredRoute.toDashboardRouteTarget(),
                boundRoute = status.boundRoute?.toDashboardBoundRoute(),
                publicIp = status.publicIp,
                activeConnections = status.metrics.activeConnections,
                totalConnections = status.metrics.totalConnections,
                rejectedConnections = status.metrics.rejectedConnections,
                bytesReceived = status.metrics.bytesReceived,
                bytesSent = status.metrics.bytesSent,
                cloudflare = cloudflare,
                root = rootState(config, status),
                startupError = status.startupError,
                warnings = buildWarnings(config, status, cloudflare),
            )
        }

        private fun listenEndpoint(
            config: AppConfig,
            status: ProxyServiceStatus,
        ): String {
            val host = status.listenHost ?: config.proxy.listenHost
            val port = status.listenPort ?: config.proxy.listenPort
            return "$host:$port"
        }

        private fun buildWarnings(
            config: AppConfig,
            status: ProxyServiceStatus,
            cloudflare: DashboardCloudflareStatus,
        ): Set<DashboardWarning> =
            buildSet {
                if (config.proxy.hasHighSecurityRisk || status.hasHighSecurityRisk) {
                    add(DashboardWarning.BroadUnauthenticatedProxy)
                }
                if (cloudflare.state == DashboardCloudflareState.Failed) {
                    add(DashboardWarning.CloudflareFailed)
                }
                if (config.root.operationsEnabled && status.rootAvailability == RootAvailabilityStatus.Unavailable) {
                    add(DashboardWarning.RootUnavailable)
                }
                if (status.startupError != null) {
                    add(DashboardWarning.StartupFailed)
                }
            }

        private fun rootState(
            config: AppConfig,
            status: ProxyServiceStatus,
        ): DashboardRootState = if (!config.root.operationsEnabled) {
            DashboardRootState.Disabled
        } else {
            status.rootAvailability.toDashboardRootState()
        }
    }
}

enum class DashboardServiceState {
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed,
}

enum class DashboardRouteTarget {
    WiFi,
    Cellular,
    Vpn,
    Automatic,
}

enum class DashboardNetworkCategory {
    WiFi,
    Cellular,
    Vpn,
}

data class DashboardBoundRoute(
    val category: DashboardNetworkCategory,
    val displayName: String,
    val isAvailable: Boolean,
)

data class DashboardCloudflareStatus(
    val state: DashboardCloudflareState,
    val remoteManagementAvailable: Boolean,
    val failureReason: String?,
)

enum class DashboardCloudflareState {
    Disabled,
    Starting,
    Connected,
    Degraded,
    Stopped,
    Failed,
}

enum class DashboardRootState {
    Disabled,
    Unknown,
    Available,
    Unavailable,
}

enum class DashboardWarning {
    BroadUnauthenticatedProxy,
    CloudflareFailed,
    RootUnavailable,
    StartupFailed,
}

private fun ProxyServiceState.toDashboardServiceState(): DashboardServiceState = when (this) {
    ProxyServiceState.Starting -> DashboardServiceState.Starting
    ProxyServiceState.Running -> DashboardServiceState.Running
    ProxyServiceState.Stopping -> DashboardServiceState.Stopping
    ProxyServiceState.Stopped -> DashboardServiceState.Stopped
    ProxyServiceState.Failed -> DashboardServiceState.Failed
}

private fun RouteTarget.toDashboardRouteTarget(): DashboardRouteTarget = when (this) {
    RouteTarget.WiFi -> DashboardRouteTarget.WiFi
    RouteTarget.Cellular -> DashboardRouteTarget.Cellular
    RouteTarget.Vpn -> DashboardRouteTarget.Vpn
    RouteTarget.Automatic -> DashboardRouteTarget.Automatic
}

private fun NetworkDescriptor.toDashboardBoundRoute(): DashboardBoundRoute = DashboardBoundRoute(
    category = category.toDashboardNetworkCategory(),
    displayName = displayName,
    isAvailable = isAvailable,
)

private fun NetworkCategory.toDashboardNetworkCategory(): DashboardNetworkCategory = when (this) {
    NetworkCategory.WiFi -> DashboardNetworkCategory.WiFi
    NetworkCategory.Cellular -> DashboardNetworkCategory.Cellular
    NetworkCategory.Vpn -> DashboardNetworkCategory.Vpn
}

private fun com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus.toDashboardCloudflareStatus(): DashboardCloudflareStatus = DashboardCloudflareStatus(
    state = state.toDashboardCloudflareState(),
    remoteManagementAvailable = isRemoteManagementAvailable,
    failureReason = failureReason?.let { CLOUDFLARE_FAILURE_SUMMARY },
)

private const val CLOUDFLARE_FAILURE_SUMMARY = "Cloudflare tunnel failed"

private fun CloudflareTunnelState.toDashboardCloudflareState(): DashboardCloudflareState = when (this) {
    CloudflareTunnelState.Disabled -> DashboardCloudflareState.Disabled
    CloudflareTunnelState.Starting -> DashboardCloudflareState.Starting
    CloudflareTunnelState.Connected -> DashboardCloudflareState.Connected
    CloudflareTunnelState.Degraded -> DashboardCloudflareState.Degraded
    CloudflareTunnelState.Stopped -> DashboardCloudflareState.Stopped
    CloudflareTunnelState.Failed -> DashboardCloudflareState.Failed
}

private fun RootAvailabilityStatus.toDashboardRootState(): DashboardRootState = when (this) {
    RootAvailabilityStatus.Unknown -> DashboardRootState.Unknown
    RootAvailabilityStatus.Available -> DashboardRootState.Available
    RootAvailabilityStatus.Unavailable -> DashboardRootState.Unavailable
}
