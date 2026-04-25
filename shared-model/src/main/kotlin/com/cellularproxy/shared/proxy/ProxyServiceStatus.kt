package com.cellularproxy.shared.proxy

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus

data class ProxyServiceStatus(
    val state: ProxyServiceState,
    val listenHost: String? = null,
    val listenPort: Int? = null,
    val configuredRoute: RouteTarget = RouteTarget.Automatic,
    val boundRoute: NetworkDescriptor? = null,
    val publicIp: String? = null,
    val hasHighSecurityRisk: Boolean = false,
    val cloudflare: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled(),
    val rootAvailability: RootAvailabilityStatus = RootAvailabilityStatus.Unknown,
    val metrics: ProxyTrafficMetrics = ProxyTrafficMetrics(),
    val startupError: ProxyStartupError? = null,
) {
    init {
        require(listenPort == null || listenPort in 1..65_535) { "Proxy listen port must be in TCP port range" }

        if (state == ProxyServiceState.Running) {
            require(!listenHost.isNullOrBlank()) { "Running proxy status requires a listen host" }
            require(listenPort != null) { "Running proxy status requires a listen port" }
        }

        if (state == ProxyServiceState.Failed) {
            require(startupError != null) { "Failed proxy status requires a startup error" }
        } else {
            require(startupError == null) { "Startup error is only valid for failed proxy status" }
        }

        if (state == ProxyServiceState.Stopped || state == ProxyServiceState.Failed) {
            require(metrics.activeConnections == 0L) {
                "Stopped or failed proxy status cannot have active connections"
            }
        }
    }

    companion object {
        fun stopped(
            configuredRoute: RouteTarget = RouteTarget.Automatic,
            cloudflare: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled(),
            rootAvailability: RootAvailabilityStatus = RootAvailabilityStatus.Unknown,
            metrics: ProxyTrafficMetrics = ProxyTrafficMetrics(),
        ): ProxyServiceStatus = ProxyServiceStatus(
            state = ProxyServiceState.Stopped,
            configuredRoute = configuredRoute,
            cloudflare = cloudflare,
            rootAvailability = rootAvailability,
            metrics = metrics,
        )

        fun running(
            listenHost: String,
            listenPort: Int,
            configuredRoute: RouteTarget,
            boundRoute: NetworkDescriptor?,
            publicIp: String?,
            hasHighSecurityRisk: Boolean,
            cloudflare: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled(),
            rootAvailability: RootAvailabilityStatus = RootAvailabilityStatus.Unknown,
            metrics: ProxyTrafficMetrics = ProxyTrafficMetrics(),
        ): ProxyServiceStatus = ProxyServiceStatus(
            state = ProxyServiceState.Running,
            listenHost = listenHost,
            listenPort = listenPort,
            configuredRoute = configuredRoute,
            boundRoute = boundRoute,
            publicIp = publicIp,
            hasHighSecurityRisk = hasHighSecurityRisk,
            cloudflare = cloudflare,
            rootAvailability = rootAvailability,
            metrics = metrics,
        )

        fun failed(
            startupError: ProxyStartupError,
            configuredRoute: RouteTarget = RouteTarget.Automatic,
            cloudflare: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled(),
            rootAvailability: RootAvailabilityStatus = RootAvailabilityStatus.Unknown,
            metrics: ProxyTrafficMetrics = ProxyTrafficMetrics(),
        ): ProxyServiceStatus = ProxyServiceStatus(
            state = ProxyServiceState.Failed,
            configuredRoute = configuredRoute,
            cloudflare = cloudflare,
            rootAvailability = rootAvailability,
            metrics = metrics,
            startupError = startupError,
        )
    }
}

enum class ProxyServiceState {
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed,
}

data class ProxyTrafficMetrics(
    val activeConnections: Long = 0,
    val totalConnections: Long = 0,
    val rejectedConnections: Long = 0,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
) {
    init {
        require(activeConnections >= 0) { "Active connection count must not be negative" }
        require(totalConnections >= 0) { "Total connection count must not be negative" }
        require(rejectedConnections >= 0) { "Rejected connection count must not be negative" }
        require(bytesReceived >= 0) { "Received byte count must not be negative" }
        require(bytesSent >= 0) { "Sent byte count must not be negative" }
        require(activeConnections <= totalConnections) {
            "Active connection count cannot exceed total connection count"
        }
    }
}

enum class ProxyStartupError {
    InvalidListenAddress,
    InvalidListenPort,
    PortAlreadyInUse,
    MissingManagementApiToken,
    UnavailableSelectedRoute,
    MissingCloudflareTunnelToken,
}
