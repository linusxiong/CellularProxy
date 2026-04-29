package com.cellularproxy.app.status

import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.rotation.RotationStatus

data class NotificationStatusModel(
    val serviceState: NotificationServiceState,
    val title: String,
    val contentText: String,
    val detailText: String,
    val warningText: String?,
    val warnings: Set<NotificationWarning>,
    val priority: NotificationPriority,
    val isOngoing: Boolean,
    val stopActionEnabled: Boolean,
) {
    companion object {
        fun from(
            config: AppConfig,
            status: ProxyServiceStatus,
            latestCloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck =
                DashboardCloudflareManagementApiCheck.NotRun,
            managementApiTokenPresent: Boolean = true,
            invalidSensitiveConfigReason: SensitiveConfigInvalidReason? = null,
            rotationStatus: RotationStatus = RotationStatus.idle(),
            rotationCooldownRemainingSeconds: Long? = null,
        ): NotificationStatusModel {
            val dashboard =
                DashboardStatusModel.from(
                    config = config,
                    status = status,
                    latestCloudflareManagementApiCheck = latestCloudflareManagementApiCheck,
                    managementApiTokenPresent = managementApiTokenPresent,
                    invalidSensitiveConfigReason = invalidSensitiveConfigReason,
                    rotationStatus = rotationStatus,
                    rotationCooldownRemainingSeconds = rotationCooldownRemainingSeconds,
                )
            val serviceState = status.state.toNotificationServiceState()
            val warnings = dashboard.warnings.toNotificationWarnings()
            return NotificationStatusModel(
                serviceState = serviceState,
                title = serviceState.title,
                contentText =
                    listOf(
                        dashboard.listenEndpoint,
                        dashboard.routeText,
                        "${dashboard.activeConnections} active",
                    ).joinToString(" | "),
                detailText =
                    listOf(
                        dashboard.publicIp?.let { "IP $it" } ?: "IP unknown",
                        "Cloudflare ${dashboard.cloudflare.state.label}",
                        "Root ${dashboard.root.label}",
                    ).joinToString(" | "),
                warningText = warnings.toWarningText(),
                warnings = warnings,
                priority =
                    when {
                        warnings.isNotEmpty() -> NotificationPriority.Warning
                        serviceState.isForeground -> NotificationPriority.Foreground
                        else -> NotificationPriority.Status
                    },
                isOngoing = serviceState.isForeground,
                stopActionEnabled =
                    serviceState == NotificationServiceState.Starting ||
                        serviceState == NotificationServiceState.Running,
            )
        }
    }
}

enum class NotificationServiceState(
    val title: String,
    val isForeground: Boolean,
) {
    Starting("CellularProxy starting", true),
    Running("CellularProxy running", true),
    Stopping("CellularProxy stopping", true),
    Stopped("CellularProxy stopped", false),
    Failed("CellularProxy failed", false),
}

enum class NotificationPriority {
    Status,
    Foreground,
    Warning,
}

enum class NotificationWarning(
    val message: String,
) {
    StartupFailed("Service startup failed"),
    BroadUnauthenticatedProxy("Proxy authentication is off on a broad listener"),
    CloudflareFailed("Cloudflare tunnel failed"),
    CloudflareDegraded("Cloudflare tunnel is degraded"),
    CloudflareManagementApiCheckFailing("Cloudflare management API check is failing"),
    RootUnavailable("Root access is unavailable"),
    SelectedRouteUnavailable("Selected route is unavailable"),
    CloudflareTokenMissing("Cloudflare tunnel token is missing"),
    CloudflareTokenInvalid("Cloudflare tunnel token is invalid"),
    ManagementApiTokenMissing("Management API token is missing"),
    SensitiveConfigurationInvalid("Sensitive configuration is invalid"),
    PortAlreadyInUse("Proxy port is already in use"),
    InvalidListenAddress("Proxy listen address is invalid"),
    InvalidListenPort("Proxy listen port is invalid"),
    InvalidMaxConcurrentConnections("Proxy connection limit is invalid"),
    RotationCooldownActive("Rotation is blocked by cooldown"),
    RotationInProgress("Rotation already in progress"),
}

private fun ProxyServiceState.toNotificationServiceState(): NotificationServiceState = when (this) {
    ProxyServiceState.Starting -> NotificationServiceState.Starting
    ProxyServiceState.Running -> NotificationServiceState.Running
    ProxyServiceState.Stopping -> NotificationServiceState.Stopping
    ProxyServiceState.Stopped -> NotificationServiceState.Stopped
    ProxyServiceState.Failed -> NotificationServiceState.Failed
}

private val DashboardStatusModel.routeText: String
    get() = boundRoute?.displayName ?: configuredRoute.label

private val DashboardRouteTarget.label: String
    get() =
        when (this) {
            DashboardRouteTarget.WiFi -> "Wi-Fi"
            DashboardRouteTarget.Cellular -> "Cellular"
            DashboardRouteTarget.Vpn -> "VPN"
            DashboardRouteTarget.Automatic -> "Automatic"
        }

private val DashboardCloudflareState.label: String
    get() =
        when (this) {
            DashboardCloudflareState.Disabled -> "disabled"
            DashboardCloudflareState.Starting -> "starting"
            DashboardCloudflareState.Connected -> "connected"
            DashboardCloudflareState.Degraded -> "degraded"
            DashboardCloudflareState.Stopped -> "stopped"
            DashboardCloudflareState.Failed -> "failed"
        }

private val DashboardRootState.label: String
    get() =
        when (this) {
            DashboardRootState.Disabled -> "disabled"
            DashboardRootState.Unknown -> "unknown"
            DashboardRootState.Available -> "available"
            DashboardRootState.Unavailable -> "unavailable"
        }

private fun Set<DashboardWarning>.toNotificationWarnings(): Set<NotificationWarning> = mapTo(linkedSetOf()) {
    when (it) {
        DashboardWarning.BroadUnauthenticatedProxy -> NotificationWarning.BroadUnauthenticatedProxy
        DashboardWarning.CloudflareFailed -> NotificationWarning.CloudflareFailed
        DashboardWarning.CloudflareDegraded -> NotificationWarning.CloudflareDegraded
        DashboardWarning.CloudflareManagementApiCheckFailing ->
            NotificationWarning.CloudflareManagementApiCheckFailing
        DashboardWarning.RootUnavailable -> NotificationWarning.RootUnavailable
        DashboardWarning.SelectedRouteUnavailable -> NotificationWarning.SelectedRouteUnavailable
        DashboardWarning.CloudflareTokenMissing -> NotificationWarning.CloudflareTokenMissing
        DashboardWarning.CloudflareTokenInvalid -> NotificationWarning.CloudflareTokenInvalid
        DashboardWarning.ManagementApiTokenMissing -> NotificationWarning.ManagementApiTokenMissing
        DashboardWarning.SensitiveConfigurationInvalid -> NotificationWarning.SensitiveConfigurationInvalid
        DashboardWarning.PortAlreadyInUse -> NotificationWarning.PortAlreadyInUse
        DashboardWarning.InvalidListenAddress -> NotificationWarning.InvalidListenAddress
        DashboardWarning.InvalidListenPort -> NotificationWarning.InvalidListenPort
        DashboardWarning.InvalidMaxConcurrentConnections -> NotificationWarning.InvalidMaxConcurrentConnections
        DashboardWarning.StartupFailed -> NotificationWarning.StartupFailed
        DashboardWarning.RotationCooldownActive -> NotificationWarning.RotationCooldownActive
        DashboardWarning.RotationInProgress -> NotificationWarning.RotationInProgress
    }
}

private fun Set<NotificationWarning>.toWarningText(): String? {
    if (isEmpty()) return null
    return listOf(
        NotificationWarning.StartupFailed,
        NotificationWarning.BroadUnauthenticatedProxy,
        NotificationWarning.CloudflareFailed,
        NotificationWarning.CloudflareDegraded,
        NotificationWarning.CloudflareManagementApiCheckFailing,
        NotificationWarning.RootUnavailable,
        NotificationWarning.SelectedRouteUnavailable,
        NotificationWarning.CloudflareTokenMissing,
        NotificationWarning.CloudflareTokenInvalid,
        NotificationWarning.ManagementApiTokenMissing,
        NotificationWarning.SensitiveConfigurationInvalid,
        NotificationWarning.PortAlreadyInUse,
        NotificationWarning.InvalidListenAddress,
        NotificationWarning.InvalidListenPort,
        NotificationWarning.InvalidMaxConcurrentConnections,
        NotificationWarning.RotationCooldownActive,
        NotificationWarning.RotationInProgress,
    ).filter { it in this }
        .joinToString(" | ") { it.message }
}
