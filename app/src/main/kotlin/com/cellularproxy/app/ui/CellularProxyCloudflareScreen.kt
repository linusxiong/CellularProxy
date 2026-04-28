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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.cloudflare.CloudflareTunnelToken
import com.cellularproxy.cloudflare.CloudflareTunnelTokenParseResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import java.net.URI

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
    managementApiRoundTripVersionProvider: () -> Long = { 0L },
    redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    onStartTunnel: () -> Unit = {},
    onStopTunnel: () -> Unit = {},
    onReconnectTunnel: () -> Unit = {},
    onTestManagementTunnel: () -> Unit = {},
    onCopyDiagnosticsText: (String) -> Unit = {},
    onRecordCloudflareAuditAction: (PersistedLogsAuditRecord) -> Unit = {},
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    val currentConfigProvider by rememberUpdatedState(configProvider)
    val currentTokenStatusProvider by rememberUpdatedState(tokenStatusProvider)
    val currentTunnelStatusProvider by rememberUpdatedState(tunnelStatusProvider)
    val currentEdgeSessionSummaryProvider by rememberUpdatedState(edgeSessionSummaryProvider)
    val currentManagementApiRoundTripProvider by rememberUpdatedState(managementApiRoundTripProvider)
    val currentManagementApiRoundTripVersionProvider by rememberUpdatedState(managementApiRoundTripVersionProvider)
    val currentRedactionSecretsProvider by rememberUpdatedState(redactionSecretsProvider)
    val currentOnStartTunnel by rememberUpdatedState(onStartTunnel)
    val currentOnStopTunnel by rememberUpdatedState(onStopTunnel)
    val currentOnReconnectTunnel by rememberUpdatedState(onReconnectTunnel)
    val currentOnTestManagementTunnel by rememberUpdatedState(onTestManagementTunnel)
    val observedConfig = configProvider()
    val observedTokenStatus = tokenStatusProvider()
    val observedTunnelStatus = tunnelStatusProvider()
    val observedEdgeSessionSummary = edgeSessionSummaryProvider()
    val observedManagementApiRoundTrip = managementApiRoundTripProvider()
    val observedManagementApiRoundTripVersion = managementApiRoundTripVersionProvider()
    val observedRedactionSecrets = redactionSecretsProvider()
    val controller =
        remember {
            CloudflareScreenController(
                configProvider = { currentConfigProvider() },
                tokenStatusProvider = { currentTokenStatusProvider() },
                tunnelStatusProvider = { currentTunnelStatusProvider() },
                edgeSessionSummaryProvider = { currentEdgeSessionSummaryProvider() },
                managementApiRoundTripProvider = { currentManagementApiRoundTripProvider() },
                managementApiRoundTripVersionProvider = { currentManagementApiRoundTripVersionProvider() },
                secretsProvider = { currentRedactionSecretsProvider() },
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
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
    LaunchedEffect(
        observedConfig,
        observedTokenStatus,
        observedTunnelStatus,
        observedEdgeSessionSummary,
        observedManagementApiRoundTrip,
        observedManagementApiRoundTripVersion,
        observedRedactionSecrets,
    ) {
        controller.handle(CloudflareScreenEvent.Refresh)
        screenState = controller.state
    }
    val dispatchEvent: (CloudflareScreenEvent) -> Unit = { event ->
        controller.handle(event)
        controller.consumeEffects().forEach { effect ->
            when (effect) {
                is CloudflareScreenEffect.CopyText -> onCopyDiagnosticsText(effect.text)
                is CloudflareScreenEffect.RecordAuditAction -> onRecordCloudflareAuditAction(effect.record)
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
            CloudflareField("Pending operation", state.pendingOperation)
        }

        CloudflareSection("Warnings") {
            if (state.warnings.isEmpty()) {
                CloudflareField("Current warnings", "None")
            } else {
                state.warnings.forEach { warning ->
                    CloudflareField("Current warning", warning.label)
                }
            }
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
    val pendingOperation: String,
    val warnings: Set<CloudflareScreenWarning>,
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
                    ?.safeCloudflareManagementHostnameLabel()
                    ?.let { LogRedactor.redact(it, secrets) }
                    ?: "Not configured"
            val lastConnectionError = tunnelStatus.lastConnectionErrorCategory(secrets)
            val redactedEdgeSessionSummary = edgeSessionSummary?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val redactedManagementApiRoundTrip = managementApiRoundTrip?.let { LogRedactor.redact(it, secrets) } ?: "Not run"
            val warnings =
                cloudflareScreenWarnings(
                    config = config,
                    tokenStatus = tokenStatus,
                    tunnelStatus = tunnelStatus,
                    managementApiRoundTrip = managementApiRoundTrip,
                )
            val warningsText = warnings.toWarningsText()
            return CloudflareScreenState(
                tunnelEnabled = tunnelEnabled,
                tokenStatus = tokenStatus.label,
                lifecycleState = tunnelStatus.state.name,
                managementHostname = managementHostname,
                lastConnectionError = lastConnectionError,
                edgeSessionSummary = redactedEdgeSessionSummary,
                managementApiRoundTrip = redactedManagementApiRoundTrip,
                pendingOperation = "None",
                warnings = warnings,
                copyableDiagnostics =
                    listOf(
                        "Tunnel enabled: $tunnelEnabled",
                        "Tunnel token: ${tokenStatus.label}",
                        "Tunnel lifecycle: ${tunnelStatus.state.name}",
                        "Management hostname: $managementHostname",
                        "Last connection error: $lastConnectionError",
                        "Edge sessions: $redactedEdgeSessionSummary",
                        "Management API round trip: $redactedManagementApiRoundTrip",
                        "Pending operation: None",
                        "Warnings: $warningsText",
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

internal enum class CloudflareScreenWarning(
    val label: String,
) {
    TunnelTokenMissing("Cloudflare tunnel token missing"),
    TunnelTokenInvalid("Cloudflare tunnel token invalid"),
    TunnelDegraded("Cloudflare tunnel degraded"),
    ManagementApiRoundTripFailing("Management API round trip failing"),
    OperationInProgress("Cloudflare operation in progress"),
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
    val token = rawToken.orEmpty()
    return when {
        token.isEmpty() -> CloudflareTokenStatus.Missing
        token != token.trim() -> CloudflareTokenStatus.Invalid
        CloudflareTunnelToken.parse(token) is CloudflareTunnelTokenParseResult.Valid -> CloudflareTokenStatus.Present
        else -> CloudflareTokenStatus.Invalid
    }
}

internal fun cloudflareTokenStatusFrom(result: SensitiveConfigLoadResult): CloudflareTokenStatus = when (result) {
    is SensitiveConfigLoadResult.Loaded -> cloudflareTokenStatusFrom(result.config.cloudflareTunnelToken)
    SensitiveConfigLoadResult.MissingRequiredSecrets -> CloudflareTokenStatus.Missing
    is SensitiveConfigLoadResult.Invalid -> CloudflareTokenStatus.Invalid
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
    private val managementApiRoundTripVersionProvider: () -> Long = { 0L },
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
    private val actionHandler: (CloudflareScreenAction) -> Unit = {},
    private val auditActionsEnabled: Boolean = false,
    private val auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    private val pendingOperations = mutableMapOf<CloudflareScreenAction, PendingCloudflareOperation>()
    private val pendingEffects = mutableListOf<CloudflareScreenEffect>()
    var state: CloudflareScreenState = buildState()
        private set

    fun handle(event: CloudflareScreenEvent) {
        when (event) {
            CloudflareScreenEvent.CopyDiagnostics -> {
                state = buildState()
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
            pendingOperations[action] =
                PendingCloudflareOperation(
                    tunnelStatus = tunnelStatusProvider(),
                    managementApiRoundTrip = managementApiRoundTripProvider(),
                    managementApiRoundTripVersion = managementApiRoundTripVersionProvider(),
                )
            actionHandler(action)
            recordAuditAction(action)?.let(pendingEffects::add)
            state = buildState()
        }
    }

    private fun buildState(): CloudflareScreenState {
        val currentTunnelStatus = tunnelStatusProvider()
        val currentEdgeSessionSummary = edgeSessionSummaryProvider()
        val currentManagementApiRoundTrip = managementApiRoundTripProvider()
        val currentManagementApiRoundTripVersion = managementApiRoundTripVersionProvider()
        val currentState =
            CloudflareScreenState.from(
                config = configProvider(),
                tunnelStatus = currentTunnelStatus,
                tokenStatus = tokenStatusProvider(),
                edgeSessionSummary = currentEdgeSessionSummary,
                managementApiRoundTrip = currentManagementApiRoundTrip,
                secrets = secretsProvider(),
            )
        val resolvedActions =
            pendingOperations
                .filter { (action, pendingOperation) ->
                    pendingOperation.isResolvedBy(
                        action = action,
                        currentTunnelStatus = currentTunnelStatus,
                        currentManagementApiRoundTrip = currentManagementApiRoundTrip,
                        currentManagementApiRoundTripVersion = currentManagementApiRoundTripVersion,
                    )
                }.keys
        resolvedActions.forEach(pendingOperations::remove)
        return currentState.withPendingOperation(
            pendingOperation = pendingOperationLabel(),
            availableActions =
                currentState.availableActions.filterNot { action ->
                    action in pendingOperations.keys ||
                        pendingOperations.keys.any(CloudflareScreenAction::isTunnelLifecycleAction) &&
                        action.isTunnelLifecycleAction()
                },
        )
    }

    private fun pendingOperationLabel(): String = pendingOperations.keys.firstOrNull()?.let { action ->
        "In progress: ${action.label}"
    } ?: "None"

    private fun recordAuditAction(action: CloudflareScreenAction): CloudflareScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        CloudflareScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.CloudflareTunnel,
                severity = LogsAuditRecordSeverity.Info,
                title = "Cloudflare ${action.auditName}",
                detail = "action=${action.auditName} lifecycle=${tunnelStatusProvider().state.name}",
            ),
        )
    } else {
        null
    }
}

private fun CloudflareScreenState.withPendingOperation(
    pendingOperation: String,
    availableActions: List<CloudflareScreenAction>,
): CloudflareScreenState {
    val currentWarnings =
        if (pendingOperation == "None") {
            warnings
        } else {
            warnings + CloudflareScreenWarning.OperationInProgress
        }
    return copy(
        pendingOperation = pendingOperation,
        warnings = currentWarnings,
        copyableDiagnostics =
            listOf(
                "Tunnel enabled: $tunnelEnabled",
                "Tunnel token: $tokenStatus",
                "Tunnel lifecycle: $lifecycleState",
                "Management hostname: $managementHostname",
                "Last connection error: $lastConnectionError",
                "Edge sessions: $edgeSessionSummary",
                "Management API round trip: $managementApiRoundTrip",
                "Pending operation: $pendingOperation",
                "Warnings: ${currentWarnings.toWarningsText()}",
            ).joinToString(separator = "\n"),
        availableActions = availableActions,
    )
}

private data class PendingCloudflareOperation(
    val tunnelStatus: CloudflareTunnelStatus,
    val managementApiRoundTrip: String?,
    val managementApiRoundTripVersion: Long,
) {
    fun isResolvedBy(
        action: CloudflareScreenAction,
        currentTunnelStatus: CloudflareTunnelStatus,
        currentManagementApiRoundTrip: String?,
        currentManagementApiRoundTripVersion: Long,
    ): Boolean = when (action) {
        CloudflareScreenAction.TestManagementTunnel ->
            managementApiRoundTrip != currentManagementApiRoundTrip ||
                managementApiRoundTripVersion != currentManagementApiRoundTripVersion
        CloudflareScreenAction.StartTunnel,
        CloudflareScreenAction.StopTunnel,
        CloudflareScreenAction.ReconnectTunnel,
        -> tunnelStatus != currentTunnelStatus
        CloudflareScreenAction.CopyDiagnostics -> true
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

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
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

private val CloudflareScreenAction.label: String
    get() =
        when (this) {
            CloudflareScreenAction.StartTunnel -> "Start tunnel"
            CloudflareScreenAction.StopTunnel -> "Stop tunnel"
            CloudflareScreenAction.ReconnectTunnel -> "Reconnect tunnel"
            CloudflareScreenAction.TestManagementTunnel -> "Test management tunnel"
            CloudflareScreenAction.CopyDiagnostics -> "Copy diagnostics"
        }

private val CloudflareScreenAction.auditName: String
    get() =
        when (this) {
            CloudflareScreenAction.StartTunnel -> "start_tunnel"
            CloudflareScreenAction.StopTunnel -> "stop_tunnel"
            CloudflareScreenAction.ReconnectTunnel -> "reconnect_tunnel"
            CloudflareScreenAction.TestManagementTunnel -> "test_management_tunnel"
            CloudflareScreenAction.CopyDiagnostics -> "copy_diagnostics"
        }

private fun CloudflareScreenAction.isTunnelLifecycleAction(): Boolean = when (this) {
    CloudflareScreenAction.StartTunnel,
    CloudflareScreenAction.StopTunnel,
    CloudflareScreenAction.ReconnectTunnel,
    -> true
    CloudflareScreenAction.TestManagementTunnel,
    CloudflareScreenAction.CopyDiagnostics,
    -> false
}

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
        if (
            tunnelStatus.state == CloudflareTunnelState.Connected ||
            tunnelStatus.state == CloudflareTunnelState.Degraded
        ) {
            actions += CloudflareScreenAction.ReconnectTunnel
        }
        if (
            tunnelStatus.state == CloudflareTunnelState.Connected &&
            config.cloudflare.managementHostnameLabel.isConfiguredManagementHostname()
        ) {
            actions += CloudflareScreenAction.TestManagementTunnel
        }
    }
    actions += CloudflareScreenAction.CopyDiagnostics
    return actions
}

private fun String?.isConfiguredManagementHostname(): Boolean = this?.trim()?.isNotEmpty() == true

private fun String.safeCloudflareManagementHostnameLabel(): String {
    val value = lineSequence().firstOrNull().orEmpty()
    if ("://" !in value) {
        return value.safeSchemeLessCloudflareHostnameLabel()
    }
    return runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme == null || host == null) {
            value.safeUrlLikeCloudflareHostnameLabel()
        } else {
            URI(scheme, null, host, uri.port, null, null, null).toString()
        }
    }.getOrElse { value.safeUrlLikeCloudflareHostnameLabel() }
}

private fun String.safeSchemeLessCloudflareHostnameLabel(): String = substringBefore('/')
    .substringBefore('\\')
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('@')

private fun String.safeUrlLikeCloudflareHostnameLabel(): String {
    val scheme = substringBefore("://").lowercase()
    val authority =
        substringAfter("://")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
    return if (scheme.isBlank()) {
        authority
    } else if (authority.isBlank()) {
        "$scheme://"
    } else {
        "$scheme://$authority"
    }
}

private fun cloudflareScreenWarnings(
    config: AppConfig,
    tokenStatus: CloudflareTokenStatus,
    tunnelStatus: CloudflareTunnelStatus,
    managementApiRoundTrip: String?,
): Set<CloudflareScreenWarning> = buildSet {
    if (config.cloudflare.enabled && tokenStatus == CloudflareTokenStatus.Missing) {
        add(CloudflareScreenWarning.TunnelTokenMissing)
    }
    if (config.cloudflare.enabled && tokenStatus == CloudflareTokenStatus.Invalid) {
        add(CloudflareScreenWarning.TunnelTokenInvalid)
    }
    if (tunnelStatus.state == CloudflareTunnelState.Degraded) {
        add(CloudflareScreenWarning.TunnelDegraded)
    }
    if (
        tunnelStatus.state == CloudflareTunnelState.Connected &&
        managementApiRoundTrip.isFailingManagementApiRoundTrip()
    ) {
        add(CloudflareScreenWarning.ManagementApiRoundTripFailing)
    }
}

private fun CloudflareTunnelStatus.lastConnectionErrorCategory(secrets: LogRedactionSecrets): String {
    val reason = failureReason
    return when {
        state == CloudflareTunnelState.Failed && reason != null -> reason.safeCloudflareErrorCategory(secrets)
        else -> "None"
    }
}

private fun String.safeCloudflareErrorCategory(secrets: LogRedactionSecrets): String {
    val redactedCategory =
        LogRedactor
            .redact(lineSequence().firstOrNull().orEmpty(), secrets)
            .substringBefore(':')
            .trim()
    return redactedCategory
        .takeIf { category ->
            category.isNotBlank() &&
                "[REDACTED]" !in category &&
                category.lowercase() !in unsafeCloudflareErrorCategories
        }
        ?: "Cloudflare tunnel failed"
}

private val unsafeCloudflareErrorCategories: Set<String> =
    setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
    )

private fun String?.isFailingManagementApiRoundTrip(): Boolean {
    val summary = this?.trim() ?: return false
    if (summary.startsWith("HTTP ")) {
        val statusCode =
            summary
                .removePrefix("HTTP ")
                .takeWhile(Char::isDigit)
                .toIntOrNull()
                ?: return false
        return statusCode !in 200..299
    }
    return summary.isNotEmpty()
}

private fun Set<CloudflareScreenWarning>.toWarningsText(): String = if (isEmpty()) {
    "None"
} else {
    joinToString(separator = " | ") { warning -> warning.label }
}
