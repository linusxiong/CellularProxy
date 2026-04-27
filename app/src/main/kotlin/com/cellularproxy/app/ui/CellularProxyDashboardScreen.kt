@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cellularproxy.app.status.DashboardBoundRoute
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.status.DashboardWarning
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceStatus

@Composable
internal fun CellularProxyDashboardScreen(
    status: DashboardStatusModel =
        DashboardStatusModel.from(
            config = AppConfig.default(),
            status = ProxyServiceStatus.stopped(),
        ),
    actionsEnabled: Boolean = false,
    onStartProxy: () -> Unit = {},
    onStopProxy: () -> Unit = {},
    onRefreshStatus: () -> Unit = {},
    onCopyProxyEndpoint: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineSmall,
        )

        DashboardActionRow(
            actionsEnabled = actionsEnabled,
            onStartProxy = onStartProxy,
            onStopProxy = onStopProxy,
            onRefreshStatus = onRefreshStatus,
            onCopyProxyEndpoint = onCopyProxyEndpoint,
        )

        DashboardSection("Service") {
            DashboardField("Service state", status.serviceState.name)
            DashboardField("Proxy endpoint", status.listenEndpoint)
            DashboardField("Proxy authentication", proxyAuthenticationSummary(status))
            DashboardField("Management API", managementApiSummary(status))
            status.startupError?.let { startupError ->
                DashboardField("Startup error", startupError.name)
            }
        }

        DashboardSection("Route") {
            DashboardField("Selected route", status.configuredRoute.name)
            DashboardField("Bound route", status.boundRoute.toDashboardText())
            DashboardField("Public IP", status.publicIp ?: "Unknown")
        }

        DashboardSection("Traffic") {
            DashboardField("Active connections", status.activeConnections.toString())
            DashboardField("Recent traffic", trafficSummary(status))
            DashboardField("Rejected connections", status.rejectedConnections.toString())
        }

        DashboardSection("Remote And Root") {
            DashboardField("Cloudflare tunnel", status.cloudflare.state.name)
            DashboardField("Remote management", managementApiSummary(status))
            DashboardField("Root availability", status.root.name)
        }

        DashboardSection("Recent high-severity errors") {
            if (status.warnings.isEmpty()) {
                Text(
                    text = "None",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                status.warnings.forEach { warning ->
                    Text(
                        text = warning.toDashboardText(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardActionRow(
    actionsEnabled: Boolean,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
    onRefreshStatus: () -> Unit,
    onCopyProxyEndpoint: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStartProxy,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start proxy")
        }
        OutlinedButton(
            onClick = onStopProxy,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop proxy")
        }
        OutlinedButton(
            onClick = onRefreshStatus,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh status")
        }
        OutlinedButton(
            onClick = onCopyProxyEndpoint,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy proxy endpoint")
        }
    }
}

@Composable
private fun DashboardSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun DashboardField(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun DashboardBoundRoute?.toDashboardText(): String = this?.let { route ->
    val availability = if (route.isAvailable) "available" else "unavailable"
    "${route.displayName} (${route.category.name}, $availability)"
} ?: "Not bound"

private fun proxyAuthenticationSummary(status: DashboardStatusModel): String = if (
    DashboardWarning.BroadUnauthenticatedProxy in status.warnings
) {
    "Broad unauthenticated listener risk"
} else {
    "No broad unauthenticated listener risk reported"
}

private fun managementApiSummary(status: DashboardStatusModel): String = if (status.cloudflare.remoteManagementAvailable) {
    "Remote management available"
} else {
    "Remote management unavailable"
}

private fun trafficSummary(status: DashboardStatusModel): String = "${status.totalConnections} total, " +
    "${status.bytesReceived} B received, ${status.bytesSent} B sent"

private fun DashboardWarning.toDashboardText(): String = when (this) {
    DashboardWarning.BroadUnauthenticatedProxy -> "Broad unauthenticated proxy listener"
    DashboardWarning.CloudflareFailed -> "Cloudflare tunnel failed"
    DashboardWarning.RootUnavailable -> "Root access is unavailable"
    DashboardWarning.SelectedRouteUnavailable -> "Selected route is unavailable"
    DashboardWarning.CloudflareTokenMissing -> "Cloudflare tunnel token is missing"
    DashboardWarning.ManagementApiTokenMissing -> "Management API token is missing"
    DashboardWarning.PortAlreadyInUse -> "Proxy port is already in use"
    DashboardWarning.StartupFailed -> "Proxy startup failed"
}
