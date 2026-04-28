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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cellularproxy.cloudflare.CloudflareTunnelToken
import com.cellularproxy.cloudflare.CloudflareTunnelTokenParseResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor

@Composable
internal fun CellularProxyCloudflareRoute(
    configProvider: () -> AppConfig = AppConfig::default,
    tokenStatusProvider: () -> CloudflareTokenStatus = {
        if (configProvider().cloudflare.tunnelTokenPresent) {
            CloudflareTokenStatus.Present
        } else {
            CloudflareTokenStatus.Missing
        }
    },
    tunnelStatusProvider: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
    edgeSessionSummaryProvider: () -> String? = { null },
    managementApiRoundTripProvider: () -> String? = { null },
    redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    onStartTunnel: () -> Unit = {},
    onStopTunnel: () -> Unit = {},
    onReconnectTunnel: () -> Unit = {},
    onTestManagementTunnel: () -> Unit = {},
    onCopyDiagnosticsText: (String) -> Unit = {},
) {
    val currentConfigProvider by rememberUpdatedState(configProvider)
    val currentTokenStatusProvider by rememberUpdatedState(tokenStatusProvider)
    val currentTunnelStatusProvider by rememberUpdatedState(tunnelStatusProvider)
    val currentEdgeSessionSummaryProvider by rememberUpdatedState(edgeSessionSummaryProvider)
    val currentManagementApiRoundTripProvider by rememberUpdatedState(managementApiRoundTripProvider)
    val currentRedactionSecretsProvider by rememberUpdatedState(redactionSecretsProvider)
    val currentOnStartTunnel by rememberUpdatedState(onStartTunnel)
    val currentOnStopTunnel by rememberUpdatedState(onStopTunnel)
    val currentOnReconnectTunnel by rememberUpdatedState(onReconnectTunnel)
    val currentOnTestManagementTunnel by rememberUpdatedState(onTestManagementTunnel)
    val controller =
        remember {
            CloudflareScreenController(
                configProvider = { currentConfigProvider() },
                tokenStatusProvider = { currentTokenStatusProvider() },
                tunnelStatusProvider = { currentTunnelStatusProvider() },
                edgeSessionSummaryProvider = { currentEdgeSessionSummaryProvider() },
                managementApiRoundTripProvider = { currentManagementApiRoundTripProvider() },
                secretsProvider = { currentRedactionSecretsProvider() },
                actionHandler = { action ->
                    when (action) {
                        CloudflareScreenAction.StartTunnel -> currentOnStartTunnel()
                        CloudflareScreenAction.StopTunnel -> currentOnStopTunnel()
                        CloudflareScreenAction.ReconnectTunnel -> currentOnReconnectTunnel()
                        CloudflareScreenAction.TestManagementTunnel -> currentOnTestManagementTunnel()
                        CloudflareScreenAction.CopyDiagnostics -> Unit
                    }
                },
            )
        }
    var screenState by remember { mutableStateOf(controller.state) }
    val dispatchEvent: (CloudflareScreenEvent) -> Unit = { event ->
        controller.handle(event)
        controller.consumeEffects().forEach { effect ->
            when (effect) {
                is CloudflareScreenEffect.CopyText -> onCopyDiagnosticsText(effect.text)
            }
        }
        screenState = controller.state
    }

    CellularProxyCloudflareScreen(
        state = screenState,
        actionsEnabled = true,
        onStartTunnel = { dispatchEvent(CloudflareScreenEvent.StartTunnel) },
        onStopTunnel = { dispatchEvent(CloudflareScreenEvent.StopTunnel) },
        onReconnectTunnel = { dispatchEvent(CloudflareScreenEvent.ReconnectTunnel) },
        onTestManagementTunnel = { dispatchEvent(CloudflareScreenEvent.TestManagementTunnel) },
        onCopyDiagnostics = { dispatchEvent(CloudflareScreenEvent.CopyDiagnostics) },
    )
}

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
    var pendingConfirmationAction by remember { mutableStateOf<CloudflareScreenAction?>(null) }

    fun performAction(action: CloudflareScreenAction) {
        when (action) {
            CloudflareScreenAction.StartTunnel -> onStartTunnel()
            CloudflareScreenAction.StopTunnel -> onStopTunnel()
            CloudflareScreenAction.ReconnectTunnel -> onReconnectTunnel()
            CloudflareScreenAction.TestManagementTunnel -> onTestManagementTunnel()
            CloudflareScreenAction.CopyDiagnostics -> onCopyDiagnostics()
        }
    }

    fun requestAction(action: CloudflareScreenAction) {
        when (cloudflareActionDispatchMode(action)) {
            CloudflareActionDispatchMode.Immediate -> performAction(action)
            CloudflareActionDispatchMode.ConfirmFirst -> pendingConfirmationAction = action
        }
    }

    pendingConfirmationAction?.let { action ->
        val canConfirm =
            cloudflareActionCanDispatch(
                action = action,
                actionsEnabled = actionsEnabled,
                availableActions = state.availableActions,
            )
        AlertDialog(
            onDismissRequest = {
                pendingConfirmationAction = null
            },
            title = {
                Text(action.confirmationTitle ?: "Confirm Cloudflare action")
            },
            text = {
                Text("Confirm this high-impact Cloudflare tunnel action.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmationAction = null
                        if (canConfirm) {
                            performAction(action)
                        }
                    },
                    enabled = canConfirm,
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingConfirmationAction = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

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
            onAction = ::requestAction,
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

internal enum class CloudflareScreenAction(
    val confirmationTitle: String? = null,
) {
    StartTunnel(
        confirmationTitle = "Confirm Cloudflare tunnel start",
    ),
    StopTunnel(
        confirmationTitle = "Confirm Cloudflare tunnel stop",
    ),
    ReconnectTunnel(
        confirmationTitle = "Confirm Cloudflare tunnel reconnect",
    ),
    TestManagementTunnel,
    CopyDiagnostics,
    ;

    val requiresConfirmation: Boolean = confirmationTitle != null
}

internal enum class CloudflareTokenStatus(
    val label: String,
) {
    Present("Present"),
    Missing("Missing"),
    Invalid("Invalid"),
}

internal fun cloudflareTokenStatusFrom(rawToken: String?): CloudflareTokenStatus {
    val token = rawToken?.trim().orEmpty()
    return when {
        token.isEmpty() -> CloudflareTokenStatus.Missing
        CloudflareTunnelToken.parse(token) is CloudflareTunnelTokenParseResult.Valid -> CloudflareTokenStatus.Present
        else -> CloudflareTokenStatus.Invalid
    }
}

internal class CloudflareScreenController(
    private val configProvider: () -> AppConfig = { AppConfig.default() },
    private val tunnelStatusProvider: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
    private val tokenStatusProvider: () -> CloudflareTokenStatus = {
        if (configProvider().cloudflare.tunnelTokenPresent) {
            CloudflareTokenStatus.Present
        } else {
            CloudflareTokenStatus.Missing
        }
    },
    private val edgeSessionSummaryProvider: () -> String? = { null },
    private val managementApiRoundTripProvider: () -> String? = { null },
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
    private val actionHandler: (CloudflareScreenAction) -> Unit = {},
) {
    private val pendingOperations = mutableMapOf<CloudflareScreenAction, CloudflareTunnelStatus>()
    private val pendingEffects = mutableListOf<CloudflareScreenEffect>()
    var state: CloudflareScreenState = buildState()
        private set

    fun handle(event: CloudflareScreenEvent) {
        when (event) {
            CloudflareScreenEvent.CopyDiagnostics -> {
                if (CloudflareScreenAction.CopyDiagnostics in state.availableActions) {
                    pendingEffects.add(CloudflareScreenEffect.CopyText(state.copyableDiagnostics))
                }
            }
            CloudflareScreenEvent.StartTunnel -> dispatchAction(CloudflareScreenAction.StartTunnel)
            CloudflareScreenEvent.StopTunnel -> dispatchAction(CloudflareScreenAction.StopTunnel)
            CloudflareScreenEvent.ReconnectTunnel -> dispatchAction(CloudflareScreenAction.ReconnectTunnel)
            CloudflareScreenEvent.TestManagementTunnel -> dispatchAction(CloudflareScreenAction.TestManagementTunnel)
            CloudflareScreenEvent.Refresh -> {
                state = buildState()
            }
        }
    }

    fun consumeEffects(): List<CloudflareScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun dispatchAction(action: CloudflareScreenAction) {
        if (action in state.availableActions) {
            pendingOperations[action] = tunnelStatusProvider()
            actionHandler(action)
            state = buildState()
        }
    }

    private fun buildState(): CloudflareScreenState {
        val currentTunnelStatus = tunnelStatusProvider()
        val currentState =
            CloudflareScreenState.from(
                config = configProvider(),
                tunnelStatus = currentTunnelStatus,
                tokenStatus = tokenStatusProvider(),
                edgeSessionSummary = edgeSessionSummaryProvider(),
                managementApiRoundTrip = managementApiRoundTripProvider(),
                secrets = secretsProvider(),
            )
        pendingOperations
            .filterValues { dispatchedStatus -> dispatchedStatus != currentTunnelStatus }
            .keys
            .forEach(pendingOperations::remove)
        return currentState.copy(
            availableActions =
                currentState.availableActions.filterNot { action ->
                    action in pendingOperations.keys
                },
        )
    }
}

internal enum class CloudflareScreenEvent {
    StartTunnel,
    StopTunnel,
    ReconnectTunnel,
    TestManagementTunnel,
    CopyDiagnostics,
    Refresh,
}

internal sealed interface CloudflareScreenEffect {
    data class CopyText(
        val text: String,
    ) : CloudflareScreenEffect
}

internal enum class CloudflareActionDispatchMode {
    Immediate,
    ConfirmFirst,
}

internal fun cloudflareActionDispatchMode(action: CloudflareScreenAction): CloudflareActionDispatchMode = if (action.requiresConfirmation) {
    CloudflareActionDispatchMode.ConfirmFirst
} else {
    CloudflareActionDispatchMode.Immediate
}

internal fun cloudflareActionCanDispatch(
    action: CloudflareScreenAction,
    actionsEnabled: Boolean,
    availableActions: List<CloudflareScreenAction>,
): Boolean = actionsEnabled && action in availableActions

@Composable
private fun CloudflareActionRow(
    actionsEnabled: Boolean,
    availableActions: List<CloudflareScreenAction>,
    onAction: (CloudflareScreenAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                onAction(CloudflareScreenAction.StartTunnel)
            },
            enabled = actionsEnabled && CloudflareScreenAction.StartTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start tunnel")
        }
        OutlinedButton(
            onClick = {
                onAction(CloudflareScreenAction.StopTunnel)
            },
            enabled = actionsEnabled && CloudflareScreenAction.StopTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop tunnel")
        }
        OutlinedButton(
            onClick = {
                onAction(CloudflareScreenAction.ReconnectTunnel)
            },
            enabled = actionsEnabled && CloudflareScreenAction.ReconnectTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reconnect tunnel")
        }
        OutlinedButton(
            onClick = {
                onAction(CloudflareScreenAction.TestManagementTunnel)
            },
            enabled = actionsEnabled && CloudflareScreenAction.TestManagementTunnel in availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test management tunnel")
        }
        OutlinedButton(
            onClick = {
                onAction(CloudflareScreenAction.CopyDiagnostics)
            },
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
