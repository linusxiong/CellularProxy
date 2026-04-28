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
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationStatus

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
    val recentTraffic: DashboardTrafficSummary? = null,
    val managementApiStatus: DashboardManagementApiStatus,
    val cloudflare: DashboardCloudflareStatus,
    val cloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck,
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
            recentTraffic: DashboardTrafficSummary? = null,
            rotationStatus: RotationStatus = RotationStatus.idle(),
            rotationCooldownRemainingSeconds: Long? = null,
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
                recentTraffic = recentTraffic,
                managementApiStatus =
                    managementApiStatus(
                        status = status,
                        managementApiTokenPresent = managementApiTokenPresent,
                    ),
                cloudflare = cloudflare,
                cloudflareManagementApiCheck = latestCloudflareManagementApiCheck,
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
                        rotationStatus = rotationStatus,
                        rotationCooldownRemainingSeconds = rotationCooldownRemainingSeconds,
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
            rotationStatus: RotationStatus,
            rotationCooldownRemainingSeconds: Long?,
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
            if (
                status.startupError == ProxyStartupError.UnavailableSelectedRoute ||
                status.boundRoute?.isAvailable == false
            ) {
                add(DashboardWarning.SelectedRouteUnavailable)
            }
            if (
                config.cloudflare.enabled &&
                !config.cloudflare.tunnelTokenPresent ||
                status.startupError == ProxyStartupError.MissingCloudflareTunnelToken
            ) {
                add(DashboardWarning.CloudflareTokenMissing)
            }
            if (
                config.cloudflare.enabled &&
                invalidSensitiveConfigReason == SensitiveConfigInvalidReason.InvalidCloudflareTunnelToken
            ) {
                add(DashboardWarning.CloudflareTokenInvalid)
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
            if (
                rotationCooldownRemainingSeconds != null &&
                rotationCooldownRemainingSeconds > 0 ||
                rotationStatus.failureReason == RotationFailureReason.CooldownActive
            ) {
                add(DashboardWarning.RotationCooldownActive)
            }
            if (rotationStatus.isActive) {
                add(DashboardWarning.RotationInProgress)
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

enum class DashboardManagementApiStatus {
    Available,
    Unavailable,
    MissingToken,
}

enum class DashboardWarning {
    BroadUnauthenticatedProxy,
    CloudflareFailed,
    CloudflareDegraded,
    CloudflareManagementApiCheckFailing,
    RootUnavailable,
    SelectedRouteUnavailable,
    CloudflareTokenMissing,
    CloudflareTokenInvalid,
    ManagementApiTokenMissing,
    SensitiveConfigurationInvalid,
    PortAlreadyInUse,
    InvalidListenAddress,
    InvalidListenPort,
    InvalidMaxConcurrentConnections,
    StartupFailed,
    RotationCooldownActive,
    RotationInProgress,
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

data class DashboardTrafficSummary(
    val windowLabel: String,
    val bytesReceived: Long,
    val bytesSent: Long,
) {
    init {
        require(windowLabel.isNotBlank()) { "Traffic summary window label must not be blank" }
        require(bytesReceived >= 0) { "Recent received byte count must not be negative" }
        require(bytesSent >= 0) { "Recent sent byte count must not be negative" }
    }
}

class DashboardRecentTrafficSampler(
    private val windowMillis: Long,
    private val nowElapsedMillis: () -> Long,
) {
    private var baselineSample: DashboardTrafficSample? = null
    private var latestSummary: DashboardTrafficSummary? = null

    init {
        require(windowMillis > 0) { "Recent traffic window must be positive" }
    }

    fun observe(metrics: ProxyTrafficMetrics): DashboardTrafficSummary? {
        val currentSample =
            DashboardTrafficSample(
                elapsedMillis = nowElapsedMillis(),
                bytesReceived = metrics.bytesReceived,
                bytesSent = metrics.bytesSent,
            )
        val baselineSample = baselineSample
        if (baselineSample == null) {
            this.baselineSample = currentSample
            return null
        }
        if (currentSample.isCounterResetFrom(baselineSample)) {
            this.baselineSample = currentSample
            latestSummary = null
            return null
        }
        val elapsedMillis = currentSample.elapsedMillis - baselineSample.elapsedMillis
        if (elapsedMillis < windowMillis) {
            return latestSummary
        }
        val summary =
            DashboardTrafficSummary(
                windowLabel = windowLabel(elapsedMillis),
                bytesReceived = currentSample.bytesReceived - baselineSample.bytesReceived,
                bytesSent = currentSample.bytesSent - baselineSample.bytesSent,
            )
        this.baselineSample = currentSample
        latestSummary = summary
        return summary
    }

    private fun windowLabel(elapsedMillis: Long): String {
        val seconds = elapsedMillis / 1_000L
        return if (seconds > 0 && elapsedMillis % 1_000L == 0L) {
            "Last $seconds seconds"
        } else {
            "Last $elapsedMillis ms"
        }
    }
}

private data class DashboardTrafficSample(
    val elapsedMillis: Long,
    val bytesReceived: Long,
    val bytesSent: Long,
) {
    init {
        require(elapsedMillis >= 0) { "Traffic sample elapsed millis must not be negative" }
    }

    fun isCounterResetFrom(previous: DashboardTrafficSample): Boolean = elapsedMillis <= previous.elapsedMillis ||
        bytesReceived < previous.bytesReceived ||
        bytesSent < previous.bytesSent
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

private fun managementApiStatus(
    status: ProxyServiceStatus,
    managementApiTokenPresent: Boolean,
): DashboardManagementApiStatus = when {
    !managementApiTokenPresent || status.startupError == ProxyStartupError.MissingManagementApiToken ->
        DashboardManagementApiStatus.MissingToken
    status.state == ProxyServiceState.Running -> DashboardManagementApiStatus.Available
    else -> DashboardManagementApiStatus.Unavailable
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
