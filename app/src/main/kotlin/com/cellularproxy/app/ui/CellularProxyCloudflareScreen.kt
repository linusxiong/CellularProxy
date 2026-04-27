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
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor

@Composable
internal fun CellularProxyCloudflareScreen(
    state: CloudflareScreenState =
        CloudflareScreenState.from(
            config = AppConfig.default(),
            tunnelStatus = CloudflareTunnelStatus.disabled(),
        ),
    actionsEnabled: Boolean = false,
    onStartTunnel: () -> Unit = {},
    onStopTunnel: () -> Unit = {},
    onReconnectTunnel: () -> Unit = {},
    onTestManagementTunnel: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
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
            text = "Cloudflare",
            style = MaterialTheme.typography.headlineSmall,
        )

        CloudflareActionRow(
            actionsEnabled = actionsEnabled,
            availableActions = state.availableActions,
            onStartTunnel = onStartTunnel,
            onStopTunnel = onStopTunnel,
            onReconnectTunnel = onReconnectTunnel,
            onTestManagementTunnel = onTestManagementTunnel,
            onCopyDiagnostics = onCopyDiagnostics,
        )

        CloudflareSection("Tunnel") {
            CloudflareField("Tunnel enabled", state.tunnelEnabled)
            CloudflareField("Tunnel token", state.tokenStatus)
            CloudflareField("Tunnel lifecycle", state.lifecycleState)
            CloudflareField("Management hostname", state.managementHostname)
        }

        CloudflareSection("Health") {
            CloudflareField("Last connection error", state.lastConnectionError)
            CloudflareField("Edge sessions", state.edgeSessionSummary)
            CloudflareField("Management API round trip", state.managementApiRoundTrip)
        }
    }
}

internal data class CloudflareScreenState(
    val tunnelEnabled: String,
    val tokenStatus: String,
    val lifecycleState: String,
    val managementHostname: String,
    val lastConnectionError: String,
    val edgeSessionSummary: String,
    val managementApiRoundTrip: String,
    val copyableDiagnostics: String,
    val availableActions: List<CloudflareScreenAction>,
) {
    companion object {
        fun from(
            config: AppConfig,
            tunnelStatus: CloudflareTunnelStatus,
            tokenStatus: CloudflareTokenStatus =
                if (config.cloudflare.tunnelTokenPresent) {
                    CloudflareTokenStatus.Present
                } else {
                    CloudflareTokenStatus.Missing
                },
            edgeSessionSummary: String? = null,
            managementApiRoundTrip: String? = null,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): CloudflareScreenState {
            val tunnelEnabled = if (config.cloudflare.enabled) "Enabled" else "Disabled"
            val managementHostname =
                config.cloudflare.managementHostnameLabel
                    ?.let { LogRedactor.redact(it, secrets) }
                    ?: "Not configured"
            val lastConnectionError = tunnelStatus.failureReason?.let { LogRedactor.redact(it, secrets) } ?: "None"
            val redactedEdgeSessionSummary = edgeSessionSummary?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val redactedManagementApiRoundTrip = managementApiRoundTrip?.let { LogRedactor.redact(it, secrets) } ?: "Not run"
            return CloudflareScreenState(
                tunnelEnabled = tunnelEnabled,
                tokenStatus = tokenStatus.label,
                lifecycleState = tunnelStatus.state.name,
                managementHostname = managementHostname,
                lastConnectionError = lastConnectionError,
                edgeSessionSummary = redactedEdgeSessionSummary,
                managementApiRoundTrip = redactedManagementApiRoundTrip,
                copyableDiagnostics =
                    listOf(
                        "Tunnel enabled: $tunnelEnabled",
                        "Tunnel token: ${tokenStatus.label}",
                        "Tunnel lifecycle: ${tunnelStatus.state.name}",
                        "Management hostname: $managementHostname",
                        "Last connection error: $lastConnectionError",
                        "Edge sessions: $redactedEdgeSessionSummary",
                        "Management API round trip: $redactedManagementApiRoundTrip",
                    ).joinToString(separator = "\n"),
                availableActions =
                    cloudflareScreenActions(
                        config = config,
                        tokenStatus = tokenStatus,
                        tunnelStatus = tunnelStatus,
                    ),
            )
        }
    }
}

internal enum class CloudflareScreenAction {
    StartTunnel,
    StopTunnel,
    ReconnectTunnel,
    TestManagementTunnel,
    CopyDiagnostics,
}

internal enum class CloudflareTokenStatus(
    val label: String,
) {
    Present("Present"),
    Missing("Missing"),
    Invalid("Invalid"),
}

@Composable
private fun CloudflareActionRow(
    actionsEnabled: Boolean,
    availableActions: List<CloudflareScreenAction>,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onReconnectTunnel: () -> Unit,
    onTestManagementTunnel: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStartTunnel,
            enabled = actionsEnabled && CloudflareScreenAction.StartTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start tunnel")
        }
        OutlinedButton(
            onClick = onStopTunnel,
            enabled = actionsEnabled && CloudflareScreenAction.StopTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop tunnel")
        }
        OutlinedButton(
            onClick = onReconnectTunnel,
            enabled = actionsEnabled && CloudflareScreenAction.ReconnectTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reconnect tunnel")
        }
        OutlinedButton(
            onClick = onTestManagementTunnel,
            enabled = actionsEnabled && CloudflareScreenAction.TestManagementTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test management tunnel")
        }
        OutlinedButton(
            onClick = onCopyDiagnostics,
            enabled = actionsEnabled && CloudflareScreenAction.CopyDiagnostics in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy diagnostics")
        }
    }
}

@Composable
private fun CloudflareSection(
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
private fun CloudflareField(
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

private fun cloudflareScreenActions(
    config: AppConfig,
    tokenStatus: CloudflareTokenStatus,
    tunnelStatus: CloudflareTunnelStatus,
): List<CloudflareScreenAction> {
    val actions = mutableListOf<CloudflareScreenAction>()
    if (config.cloudflare.enabled && tokenStatus == CloudflareTokenStatus.Present) {
        when (tunnelStatus.state) {
            CloudflareTunnelState.Disabled,
            CloudflareTunnelState.Stopped,
            CloudflareTunnelState.Failed,
            -> actions += CloudflareScreenAction.StartTunnel
            CloudflareTunnelState.Starting,
            CloudflareTunnelState.Connected,
            CloudflareTunnelState.Degraded,
            -> actions += CloudflareScreenAction.StopTunnel
        }
        if (tunnelStatus.state == CloudflareTunnelState.Degraded) {
            actions += CloudflareScreenAction.ReconnectTunnel
        }
        if (tunnelStatus.state == CloudflareTunnelState.Connected) {
            actions += CloudflareScreenAction.TestManagementTunnel
        }
    }
    actions += CloudflareScreenAction.CopyDiagnostics
    return actions
}
