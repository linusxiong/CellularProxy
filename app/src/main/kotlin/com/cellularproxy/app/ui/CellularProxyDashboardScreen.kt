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
import com.cellularproxy.app.status.DashboardBoundRoute
import com.cellularproxy.app.status.DashboardLogSeverity
import com.cellularproxy.app.status.DashboardLogSummary
import com.cellularproxy.app.status.DashboardManagementApiStatus
import com.cellularproxy.app.status.DashboardRecentError
import com.cellularproxy.app.status.DashboardServiceState
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.status.DashboardWarning
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceStatus

@Composable
internal fun CellularProxyDashboardRoute(
    statusProvider: () -> DashboardStatusModel = {
        DashboardStatusModel.from(
            config = AppConfig.default(),
            status = ProxyServiceStatus.stopped(),
        )
    },
    onStartProxyService: () -> Unit = {},
    onStopProxyService: () -> Unit = {},
    onRestartProxyService: () -> Unit = {},
    onRefreshStatus: () -> Unit = {},
    onOpenRiskDetails: () -> Unit = {},
    onOpenCloudflare: () -> Unit = {},
    onOpenRotation: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onCopyProxyEndpointText: (String) -> Unit = {},
    onRecordDashboardAuditAction: (PersistedLogsAuditRecord) -> Unit = {},
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    val currentStatusProvider by rememberUpdatedState(statusProvider)
    val currentOnStartProxyService by rememberUpdatedState(onStartProxyService)
    val currentOnStopProxyService by rememberUpdatedState(onStopProxyService)
    val currentOnRestartProxyService by rememberUpdatedState(onRestartProxyService)
    val currentOnRefreshStatus by rememberUpdatedState(onRefreshStatus)
    val currentOnOpenRiskDetails by rememberUpdatedState(onOpenRiskDetails)
    val currentOnOpenCloudflare by rememberUpdatedState(onOpenCloudflare)
    val currentOnOpenRotation by rememberUpdatedState(onOpenRotation)
    val currentOnOpenLogs by rememberUpdatedState(onOpenLogs)
    val currentOnOpenDiagnostics by rememberUpdatedState(onOpenDiagnostics)
    val observedStatus = statusProvider()
    val controller =
        remember {
            DashboardScreenController(
                statusProvider = { currentStatusProvider() },
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
                actionHandler = { action ->
                    when (action) {
                        DashboardScreenAction.StartProxy -> currentOnStartProxyService()
                        DashboardScreenAction.StopProxy -> currentOnStopProxyService()
                        DashboardScreenAction.RestartProxy -> currentOnRestartProxyService()
                        DashboardScreenAction.RefreshStatus -> currentOnRefreshStatus()
                        DashboardScreenAction.OpenRiskDetails -> currentOnOpenRiskDetails()
                        DashboardScreenAction.OpenCloudflare -> currentOnOpenCloudflare()
                        DashboardScreenAction.OpenRotation -> currentOnOpenRotation()
                        DashboardScreenAction.OpenLogs -> currentOnOpenLogs()
                        DashboardScreenAction.OpenDiagnostics -> currentOnOpenDiagnostics()
                        else -> Unit
                    }
                },
            )
        }
    var screenState by remember { mutableStateOf(controller.state) }
    val dispatchEvent: (DashboardScreenEvent) -> Unit = { event ->
        controller.handle(event)
        controller.consumeEffects().forEach { effect ->
            when (effect) {
                is DashboardScreenEffect.CopyText -> onCopyProxyEndpointText(effect.text)
                is DashboardScreenEffect.RecordAuditAction -> onRecordDashboardAuditAction(effect.record)
            }
        }
        screenState = controller.state
    }
    LaunchedEffect(observedStatus) {
        controller.handle(DashboardScreenEvent.Refresh)
        screenState = controller.state
    }

    CellularProxyDashboardScreen(
        state = screenState,
        actionsEnabled = true,
        onStartProxy = { dispatchEvent(DashboardScreenEvent.StartProxy) },
        onStopProxy = { dispatchEvent(DashboardScreenEvent.StopProxy) },
        onRestartProxy = { dispatchEvent(DashboardScreenEvent.RestartProxy) },
        onRefreshStatus = { dispatchEvent(DashboardScreenEvent.RefreshStatus) },
        onCopyProxyEndpoint = { dispatchEvent(DashboardScreenEvent.CopyProxyEndpoint) },
        onOpenRiskDetails = { dispatchEvent(DashboardScreenEvent.OpenRiskDetails) },
        onOpenCloudflare = { dispatchEvent(DashboardScreenEvent.OpenCloudflare) },
        onOpenRotation = { dispatchEvent(DashboardScreenEvent.OpenRotation) },
        onOpenLogs = { dispatchEvent(DashboardScreenEvent.OpenLogs) },
        onOpenDiagnostics = { dispatchEvent(DashboardScreenEvent.OpenDiagnostics) },
    )
}

@Composable
internal fun CellularProxyDashboardScreen(
    state: DashboardScreenState =
        DashboardScreenState.from(
            DashboardStatusModel.from(
                config = AppConfig.default(),
                status = ProxyServiceStatus.stopped(),
            ),
        ),
    actionsEnabled: Boolean = false,
    onStartProxy: () -> Unit = {},
    onStopProxy: () -> Unit = {},
    onRestartProxy: () -> Unit = {},
    onRefreshStatus: () -> Unit = {},
    onCopyProxyEndpoint: () -> Unit = {},
    onOpenRiskDetails: () -> Unit = {},
    onOpenCloudflare: () -> Unit = {},
    onOpenRotation: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val screenState = state
    val status = screenState.status
    var pendingConfirmationAction by remember { mutableStateOf<DashboardScreenAction?>(null) }

    fun performAction(action: DashboardScreenAction) {
        when (action) {
            DashboardScreenAction.StartProxy -> onStartProxy()
            DashboardScreenAction.StopProxy -> onStopProxy()
            DashboardScreenAction.RestartProxy -> onRestartProxy()
            DashboardScreenAction.RefreshStatus -> onRefreshStatus()
            DashboardScreenAction.CopyProxyEndpoint -> onCopyProxyEndpoint()
            DashboardScreenAction.OpenRiskDetails -> onOpenRiskDetails()
            DashboardScreenAction.OpenCloudflare -> onOpenCloudflare()
            DashboardScreenAction.OpenRotation -> onOpenRotation()
            DashboardScreenAction.OpenLogs -> onOpenLogs()
            DashboardScreenAction.OpenDiagnostics -> onOpenDiagnostics()
        }
    }

    fun requestAction(action: DashboardScreenAction) {
        when (dashboardActionDispatchMode(action)) {
            DashboardActionDispatchMode.Immediate -> performAction(action)
            DashboardActionDispatchMode.ConfirmFirst -> pendingConfirmationAction = action
        }
    }

    pendingConfirmationAction?.let { action ->
        val canConfirm =
            dashboardActionCanDispatch(
                action = action,
                actionsEnabled = actionsEnabled,
                availableActions = screenState.availableActions,
            )
        AlertDialog(
            onDismissRequest = {
                pendingConfirmationAction = null
            },
            title = {
                Text(action.confirmationTitle ?: "Confirm dashboard action")
            },
            text = {
                Text("Confirm this high-impact proxy service action.")
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
            text = "Dashboard",
            style = MaterialTheme.typography.headlineSmall,
        )

        DashboardActionRow(
            actionsEnabled = actionsEnabled,
            availableActions = screenState.availableActions,
            onAction = ::requestAction,
        )

        DashboardSection("Service") {
            DashboardField("Service state", status.serviceState.name)
            DashboardField("Pending operation", screenState.pendingOperation)
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
            DashboardField("Recent traffic", recentTrafficSummary(status))
            DashboardField("Total traffic", totalTrafficSummary(status))
            DashboardField("Rejected connections", status.rejectedConnections.toString())
        }

        DashboardSection("Remote And Root") {
            DashboardField("Cloudflare tunnel", status.cloudflare.state.name)
            DashboardField("Remote management", remoteManagementSummary(status))
            DashboardField("Cloudflare management API", cloudflareManagementApiCheckSummary(status))
            DashboardField("Root availability", status.root.name)
        }

        DashboardSection("Recent high-severity errors") {
            if (screenState.recentHighSeverityErrors.isEmpty()) {
                Text(
                    text = "None",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                screenState.recentHighSeverityErrors.forEach { recentError ->
                    Text(
                        text = recentError,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        DashboardSection("Risk states") {
            if (screenState.riskWarnings.isEmpty() && screenState.riskItems.isEmpty()) {
                Text(
                    text = "None",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                screenState.riskItems.forEach { riskItem ->
                    OutlinedButton(
                        onClick = { requestAction(riskItem.action) },
                        enabled =
                            dashboardActionCanDispatch(
                                action = riskItem.action,
                                actionsEnabled = actionsEnabled,
                                availableActions = screenState.availableActions,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(riskItem.label)
                    }
                }
                val actionableRiskLabels = screenState.riskItems.map(DashboardRiskItem::label).toSet()
                screenState.riskWarnings.filterNot(actionableRiskLabels::contains).forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

internal data class DashboardScreenState(
    val status: DashboardStatusModel,
    val riskWarnings: List<String>,
    val riskItems: List<DashboardRiskItem>,
    val recentHighSeverityErrors: List<String>,
    val pendingOperation: String = "None",
    val availableActions: List<DashboardScreenAction>,
) {
    companion object {
        fun from(status: DashboardStatusModel): DashboardScreenState = DashboardScreenState(
            status = status,
            riskWarnings = status.warnings.map(DashboardWarning::toDashboardText),
            riskItems = status.warnings.mapNotNull(DashboardWarning::toDashboardRiskItem),
            recentHighSeverityErrors = status.recentHighSeverityErrors.map(DashboardRecentError::toDashboardText),
            pendingOperation = "None",
            availableActions = status.availableActions(),
        )
    }
}

internal data class DashboardRiskItem(
    val label: String,
    val action: DashboardScreenAction,
)

internal enum class DashboardScreenAction(
    val confirmationTitle: String? = null,
) {
    StartProxy,
    StopProxy(
        confirmationTitle = "Confirm proxy service stop",
    ),
    RestartProxy(
        confirmationTitle = "Confirm proxy service restart",
    ),
    RefreshStatus,
    CopyProxyEndpoint,
    OpenRiskDetails,
    OpenCloudflare,
    OpenRotation,
    OpenLogs,
    OpenDiagnostics,
    ;

    val requiresConfirmation: Boolean = confirmationTitle != null
}

internal enum class DashboardActionDispatchMode {
    Immediate,
    ConfirmFirst,
}

internal fun dashboardActionDispatchMode(action: DashboardScreenAction): DashboardActionDispatchMode = if (action.requiresConfirmation) {
    DashboardActionDispatchMode.ConfirmFirst
} else {
    DashboardActionDispatchMode.Immediate
}

internal fun dashboardActionCanDispatch(
    action: DashboardScreenAction,
    actionsEnabled: Boolean,
    availableActions: List<DashboardScreenAction>,
): Boolean = actionsEnabled && action in availableActions

internal class DashboardScreenController(
    private val statusProvider: () -> DashboardStatusModel = {
        DashboardStatusModel.from(
            config = AppConfig.default(),
            status = ProxyServiceStatus.stopped(),
        )
    },
    private val auditActionsEnabled: Boolean = false,
    private val auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
    private val actionHandler: (DashboardScreenAction) -> Unit = {},
) {
    private var lastObservedServiceState: DashboardServiceState = statusProvider().serviceState
    private val pendingLifecycleActions = mutableSetOf<DashboardScreenAction>()
    private val pendingEffects = mutableListOf<DashboardScreenEffect>()
    var state: DashboardScreenState = buildState()
        private set

    fun handle(event: DashboardScreenEvent) {
        refreshPendingActions()
        when (event) {
            DashboardScreenEvent.StartProxy -> dispatchAction(DashboardScreenAction.StartProxy)
            DashboardScreenEvent.StopProxy -> dispatchAction(DashboardScreenAction.StopProxy)
            DashboardScreenEvent.RestartProxy -> dispatchAction(DashboardScreenAction.RestartProxy)
            DashboardScreenEvent.RefreshStatus -> dispatchAction(DashboardScreenAction.RefreshStatus)
            DashboardScreenEvent.OpenRiskDetails -> dispatchAction(DashboardScreenAction.OpenRiskDetails)
            DashboardScreenEvent.OpenCloudflare -> dispatchAction(DashboardScreenAction.OpenCloudflare)
            DashboardScreenEvent.OpenRotation -> dispatchAction(DashboardScreenAction.OpenRotation)
            DashboardScreenEvent.OpenLogs -> dispatchAction(DashboardScreenAction.OpenLogs)
            DashboardScreenEvent.OpenDiagnostics -> dispatchAction(DashboardScreenAction.OpenDiagnostics)
            DashboardScreenEvent.Refresh -> state = buildState()
            DashboardScreenEvent.CopyProxyEndpoint -> {
                if (DashboardScreenAction.CopyProxyEndpoint in state.availableActions) {
                    pendingEffects.add(DashboardScreenEffect.CopyText(state.status.listenEndpoint))
                }
            }
        }
    }

    fun consumeEffects(): List<DashboardScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun dispatchAction(action: DashboardScreenAction) {
        if (action !in state.availableActions) {
            return
        }
        if (action.isLifecycleAction) {
            pendingLifecycleActions.add(action)
        }
        recordAuditAction(action)?.let(pendingEffects::add)
        actionHandler(action)
        refreshPendingActions()
    }

    private fun refreshPendingActions() {
        val currentServiceState = statusProvider().serviceState
        if (currentServiceState != lastObservedServiceState) {
            pendingLifecycleActions.clear()
            lastObservedServiceState = currentServiceState
        }
        state = buildState()
    }

    private fun buildState(): DashboardScreenState = DashboardScreenState
        .from(statusProvider())
        .withoutPendingLifecycleActions(pendingLifecycleActions)

    private fun recordAuditAction(action: DashboardScreenAction): DashboardScreenEffect.RecordAuditAction? = if (
        auditActionsEnabled &&
        action.isAuditedOperationalAction
    ) {
        DashboardScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.AppRuntime,
                severity = LogsAuditRecordSeverity.Info,
                title = "Dashboard ${action.auditName}",
                detail = "action=${action.auditName} serviceState=${statusProvider().serviceState.name}",
            ),
        )
    } else {
        null
    }
}

internal fun dashboardLogSummariesFromLogsAuditRows(
    rows: List<LogsAuditScreenInputRow>,
): List<DashboardLogSummary> = rows.map { row ->
    DashboardLogSummary(
        id = row.id,
        occurredAtEpochMillis = row.occurredAtEpochMillis,
        severity = row.severity.toDashboardLogSeverity(),
        title = row.title,
        detail = row.detail,
    )
}

private fun LogsAuditScreenSeverity.toDashboardLogSeverity(): DashboardLogSeverity = when (this) {
    LogsAuditScreenSeverity.Info -> DashboardLogSeverity.Info
    LogsAuditScreenSeverity.Warning -> DashboardLogSeverity.Warning
    LogsAuditScreenSeverity.Failed -> DashboardLogSeverity.Failed
}

internal sealed interface DashboardScreenEvent {
    data object StartProxy : DashboardScreenEvent

    data object StopProxy : DashboardScreenEvent

    data object RestartProxy : DashboardScreenEvent

    data object RefreshStatus : DashboardScreenEvent

    data object CopyProxyEndpoint : DashboardScreenEvent

    data object OpenRiskDetails : DashboardScreenEvent

    data object OpenCloudflare : DashboardScreenEvent

    data object OpenRotation : DashboardScreenEvent

    data object OpenLogs : DashboardScreenEvent

    data object OpenDiagnostics : DashboardScreenEvent

    data object Refresh : DashboardScreenEvent
}

internal sealed interface DashboardScreenEffect {
    data class CopyText(
        val text: String,
    ) : DashboardScreenEffect

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
    ) : DashboardScreenEffect
}

private val DashboardScreenAction.isLifecycleAction: Boolean
    get() =
        this == DashboardScreenAction.StartProxy ||
            this == DashboardScreenAction.StopProxy ||
            this == DashboardScreenAction.RestartProxy

private val DashboardScreenAction.isAuditedOperationalAction: Boolean
    get() =
        isLifecycleAction ||
            this == DashboardScreenAction.RefreshStatus

private val DashboardScreenAction.auditName: String
    get() =
        when (this) {
            DashboardScreenAction.StartProxy -> "start_proxy"
            DashboardScreenAction.StopProxy -> "stop_proxy"
            DashboardScreenAction.RestartProxy -> "restart_proxy"
            DashboardScreenAction.RefreshStatus -> "refresh_status"
            DashboardScreenAction.CopyProxyEndpoint -> "copy_proxy_endpoint"
            DashboardScreenAction.OpenRiskDetails -> "open_risk_details"
            DashboardScreenAction.OpenCloudflare -> "open_cloudflare"
            DashboardScreenAction.OpenRotation -> "open_rotation"
            DashboardScreenAction.OpenLogs -> "open_logs"
            DashboardScreenAction.OpenDiagnostics -> "open_diagnostics"
        }

private fun DashboardScreenState.withoutPendingLifecycleActions(
    pendingLifecycleActions: Set<DashboardScreenAction>,
): DashboardScreenState {
    val pendingOperation = pendingLifecycleActions.pendingOperationLabel()
    return copy(
        pendingOperation = pendingOperation,
        availableActions =
            if (pendingLifecycleActions.isNotEmpty()) {
                availableActions.filterNot(DashboardScreenAction::isLifecycleAction)
            } else {
                availableActions
            },
    )
}

private fun Set<DashboardScreenAction>.pendingOperationLabel(): String = firstOrNull()?.let { action ->
    "In progress: ${action.operationLabel}"
} ?: "None"

private val DashboardScreenAction.operationLabel: String
    get() =
        when (this) {
            DashboardScreenAction.StartProxy -> "Start proxy"
            DashboardScreenAction.StopProxy -> "Stop proxy"
            DashboardScreenAction.RestartProxy -> "Restart proxy service"
            DashboardScreenAction.RefreshStatus -> "Refresh status"
            DashboardScreenAction.CopyProxyEndpoint -> "Copy proxy endpoint"
            DashboardScreenAction.OpenRiskDetails -> "Open risk details"
            DashboardScreenAction.OpenCloudflare -> "Open Cloudflare"
            DashboardScreenAction.OpenRotation -> "Open rotation"
            DashboardScreenAction.OpenLogs -> "Open logs"
            DashboardScreenAction.OpenDiagnostics -> "Open diagnostics"
        }

@Composable
private fun DashboardActionRow(
    actionsEnabled: Boolean,
    availableActions: List<DashboardScreenAction>,
    onAction: (DashboardScreenAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                onAction(DashboardScreenAction.StartProxy)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.StartProxy,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start proxy")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.StopProxy)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.StopProxy,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop proxy")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.RestartProxy)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.RestartProxy,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restart proxy service")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.RefreshStatus)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.RefreshStatus,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh status")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.CopyProxyEndpoint)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.CopyProxyEndpoint,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy proxy endpoint")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.OpenRiskDetails)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.OpenRiskDetails,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open risk details")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.OpenCloudflare)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.OpenCloudflare,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Cloudflare")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.OpenRotation)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.OpenRotation,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open rotation")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.OpenLogs)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.OpenLogs,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open logs")
        }
        OutlinedButton(
            onClick = {
                onAction(DashboardScreenAction.OpenDiagnostics)
            },
            enabled =
                dashboardActionCanDispatch(
                    action = DashboardScreenAction.OpenDiagnostics,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open diagnostics")
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

private fun managementApiSummary(status: DashboardStatusModel): String = when (status.managementApiStatus) {
    DashboardManagementApiStatus.Available -> "Local management available"
    DashboardManagementApiStatus.Unavailable -> "Local management unavailable"
    DashboardManagementApiStatus.MissingToken -> "Management API token missing"
}

private fun remoteManagementSummary(status: DashboardStatusModel): String = if (status.cloudflare.remoteManagementAvailable) {
    "Remote management available"
} else {
    "Remote management unavailable"
}

private fun cloudflareManagementApiCheckSummary(status: DashboardStatusModel): String = when (
    status.cloudflareManagementApiCheck
) {
    com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck.NotRun -> "Not run"
    com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck.Running -> "Running"
    com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck.Passed -> "Passed"
    com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck.Failed -> "Failed"
}

private fun recentTrafficSummary(status: DashboardStatusModel): String = status.recentTraffic?.let { traffic ->
    "${traffic.windowLabel}: ${traffic.bytesReceived} B received, ${traffic.bytesSent} B sent"
} ?: "Unavailable"

private fun totalTrafficSummary(status: DashboardStatusModel): String = "${status.totalConnections} total, " +
    "${status.bytesReceived} B received, ${status.bytesSent} B sent"

private fun DashboardWarning.toDashboardText(): String = when (this) {
    DashboardWarning.BroadUnauthenticatedProxy -> "Broad unauthenticated proxy listener"
    DashboardWarning.CloudflareFailed -> "Cloudflare tunnel failed"
    DashboardWarning.CloudflareDegraded -> "Cloudflare tunnel is degraded"
    DashboardWarning.CloudflareManagementApiCheckFailing -> "Cloudflare management API check is failing"
    DashboardWarning.RootUnavailable -> "Root access is unavailable"
    DashboardWarning.SelectedRouteUnavailable -> "Selected route is unavailable"
    DashboardWarning.CloudflareTokenMissing -> "Cloudflare tunnel token is missing"
    DashboardWarning.CloudflareTokenInvalid -> "Cloudflare tunnel token is invalid"
    DashboardWarning.ManagementApiTokenMissing -> "Management API token is missing"
    DashboardWarning.SensitiveConfigurationInvalid -> "Sensitive configuration is invalid"
    DashboardWarning.PortAlreadyInUse -> "Proxy port is already in use"
    DashboardWarning.InvalidListenAddress -> "Proxy listen address is invalid"
    DashboardWarning.InvalidListenPort -> "Proxy listen port is invalid"
    DashboardWarning.InvalidMaxConcurrentConnections -> "Proxy connection limit is invalid"
    DashboardWarning.StartupFailed -> "Proxy startup failed"
    DashboardWarning.RotationCooldownActive -> "Rotation blocked by cooldown"
    DashboardWarning.RotationInProgress -> "Rotation already in progress"
}

private fun DashboardWarning.toDashboardRiskItem(): DashboardRiskItem? = when (this) {
    DashboardWarning.CloudflareTokenMissing ->
        DashboardRiskItem(
            label = toDashboardText(),
            action = DashboardScreenAction.OpenCloudflare,
        )
    else -> null
}

private fun DashboardRecentError.toDashboardText(): String = "$title: $detail"

private fun DashboardStatusModel.availableActions(): List<DashboardScreenAction> = buildList {
    when (serviceState) {
        DashboardServiceState.Starting,
        DashboardServiceState.Running,
        -> {
            add(DashboardScreenAction.StopProxy)
            if (serviceState == DashboardServiceState.Running) {
                add(DashboardScreenAction.RestartProxy)
            }
        }
        DashboardServiceState.Stopping -> Unit
        DashboardServiceState.Stopped,
        DashboardServiceState.Failed,
        -> add(DashboardScreenAction.StartProxy)
    }
    add(DashboardScreenAction.RefreshStatus)
    add(DashboardScreenAction.CopyProxyEndpoint)
    add(DashboardScreenAction.OpenRiskDetails)
    add(DashboardScreenAction.OpenCloudflare)
    add(DashboardScreenAction.OpenRotation)
    add(DashboardScreenAction.OpenLogs)
    add(DashboardScreenAction.OpenDiagnostics)
}
