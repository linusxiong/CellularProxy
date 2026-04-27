package com.cellularproxy.app.status

import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
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
    val recentHighSeverityErrors: List<DashboardRecentError> = emptyList(),
) {
    companion object {
        fun from(
            config: AppConfig,
            status: ProxyServiceStatus,
            recentLogs: List<DashboardLogSummary> = emptyList(),
            redactionSecrets: LogRedactionSecrets = LogRedactionSecrets(),
            latestCloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck =
                DashboardCloudflareManagementApiCheck.NotRun,
            managementApiTokenPresent: Boolean = true,
            invalidSensitiveConfigReason: SensitiveConfigInvalidReason? = null,
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
                warnings =
                    buildWarnings(
                        config = config,
                        status = status,
                        cloudflare = cloudflare,
                        latestCloudflareManagementApiCheck = latestCloudflareManagementApiCheck,
                        managementApiTokenPresent = managementApiTokenPresent,
                        invalidSensitiveConfigReason = invalidSensitiveConfigReason,
                    ),
                recentHighSeverityErrors = recentLogs.toDashboardRecentErrors(redactionSecrets),
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
            latestCloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck,
            managementApiTokenPresent: Boolean,
            invalidSensitiveConfigReason: SensitiveConfigInvalidReason?,
        ): Set<DashboardWarning> = buildSet {
            if (config.proxy.hasHighSecurityRisk || status.hasHighSecurityRisk) {
                add(DashboardWarning.BroadUnauthenticatedProxy)
            }
            if (cloudflare.state == DashboardCloudflareState.Failed) {
                add(DashboardWarning.CloudflareFailed)
            }
            if (cloudflare.state == DashboardCloudflareState.Degraded) {
                add(DashboardWarning.CloudflareDegraded)
            }
            if (
                cloudflare.state == DashboardCloudflareState.Connected &&
                latestCloudflareManagementApiCheck == DashboardCloudflareManagementApiCheck.Failed
            ) {
                add(DashboardWarning.CloudflareManagementApiCheckFailing)
            }
            if (config.root.operationsEnabled && status.rootAvailability == RootAvailabilityStatus.Unavailable) {
                add(DashboardWarning.RootUnavailable)
            }
            if (status.startupError == ProxyStartupError.UnavailableSelectedRoute) {
                add(DashboardWarning.SelectedRouteUnavailable)
            }
            if (
                config.cloudflare.enabled &&
                !config.cloudflare.tunnelTokenPresent ||
                status.startupError == ProxyStartupError.MissingCloudflareTunnelToken
            ) {
                add(DashboardWarning.CloudflareTokenMissing)
            }
            if (!managementApiTokenPresent || status.startupError == ProxyStartupError.MissingManagementApiToken) {
                add(DashboardWarning.ManagementApiTokenMissing)
            }
            if (invalidSensitiveConfigReason != null) {
                add(DashboardWarning.SensitiveConfigurationInvalid)
            }
            if (status.startupError == ProxyStartupError.PortAlreadyInUse) {
                add(DashboardWarning.PortAlreadyInUse)
            }
            when (status.startupError) {
                ProxyStartupError.InvalidListenAddress -> add(DashboardWarning.InvalidListenAddress)
                ProxyStartupError.InvalidListenPort -> add(DashboardWarning.InvalidListenPort)
                ProxyStartupError.InvalidMaxConcurrentConnections ->
                    add(DashboardWarning.InvalidMaxConcurrentConnections)
                else -> Unit
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
    CloudflareDegraded,
    CloudflareManagementApiCheckFailing,
    RootUnavailable,
    SelectedRouteUnavailable,
    CloudflareTokenMissing,
    ManagementApiTokenMissing,
    SensitiveConfigurationInvalid,
    PortAlreadyInUse,
    InvalidListenAddress,
    InvalidListenPort,
    InvalidMaxConcurrentConnections,
    StartupFailed,
}

enum class DashboardCloudflareManagementApiCheck {
    NotRun,
    Running,
    Passed,
    Failed,
}

enum class DashboardLogSeverity {
    Info,
    Warning,
    Failed,
}

data class DashboardLogSummary(
    val id: String,
    val occurredAtEpochMillis: Long,
    val severity: DashboardLogSeverity,
    val title: String,
    val detail: String,
)

data class DashboardRecentError(
    val id: String,
    val occurredAtEpochMillis: Long,
    val title: String,
    val detail: String,
)

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

private fun List<DashboardLogSummary>.toDashboardRecentErrors(
    redactionSecrets: LogRedactionSecrets,
): List<DashboardRecentError> = filter { log ->
    log.severity == DashboardLogSeverity.Failed
}.sortedByDescending(DashboardLogSummary::occurredAtEpochMillis)
    .take(MAX_RECENT_HIGH_SEVERITY_ERRORS)
    .map { log ->
        DashboardRecentError(
            id = log.id,
            occurredAtEpochMillis = log.occurredAtEpochMillis,
            title = LogRedactor.redact(log.title, redactionSecrets),
            detail = LogRedactor.redact(log.detail, redactionSecrets),
        )
    }

private const val MAX_RECENT_HIGH_SEVERITY_ERRORS = 3
