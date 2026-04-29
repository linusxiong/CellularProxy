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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.viewmodel.RotationViewModel
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus

@Composable
internal fun CellularProxyRotationRoute(
    configProvider: () -> AppConfig = AppConfig::default,
    rotationStatusProvider: () -> RotationStatus = { RotationStatus.idle() },
    currentPublicIpProvider: () -> String? = { null },
    rootAvailabilityProvider: () -> RootAvailabilityStatus = { RootAvailabilityStatus.Unknown },
    cooldownRemainingSecondsProvider: () -> Long? = { null },
    activeConnectionsProvider: () -> Long = { 0 },
    redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    onCheckRoot: () -> Unit = {},
    onProbeCurrentPublicIp: () -> Unit = {},
    onRotateMobileData: () -> Unit = {},
    onRotateAirplaneMode: () -> Unit = {},
    onCopyRotationDiagnosticsText: (String) -> Unit = {},
    onRecordRotationAuditAction: (PersistedLogsAuditRecord) -> Unit = {},
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    val currentConfigProvider by rememberUpdatedState(configProvider)
    val currentRotationStatusProvider by rememberUpdatedState(rotationStatusProvider)
    val currentCurrentPublicIpProvider by rememberUpdatedState(currentPublicIpProvider)
    val currentRootAvailabilityProvider by rememberUpdatedState(rootAvailabilityProvider)
    val currentCooldownRemainingSecondsProvider by rememberUpdatedState(cooldownRemainingSecondsProvider)
    val currentActiveConnectionsProvider by rememberUpdatedState(activeConnectionsProvider)
    val currentRedactionSecretsProvider by rememberUpdatedState(redactionSecretsProvider)
    val currentOnCheckRoot by rememberUpdatedState(onCheckRoot)
    val currentOnProbeCurrentPublicIp by rememberUpdatedState(onProbeCurrentPublicIp)
    val currentOnRotateMobileData by rememberUpdatedState(onRotateMobileData)
    val currentOnRotateAirplaneMode by rememberUpdatedState(onRotateAirplaneMode)
    val currentAuditOccurredAtEpochMillisProvider by rememberUpdatedState(auditOccurredAtEpochMillisProvider)
    val observedConfig = configProvider()
    val observedRotationStatus = rotationStatusProvider()
    val observedCurrentPublicIp = currentPublicIpProvider()
    val observedRootAvailability = rootAvailabilityProvider()
    val observedCooldownRemainingSeconds = cooldownRemainingSecondsProvider()
    val observedActiveConnections = activeConnectionsProvider()
    val observedRedactionSecrets = redactionSecretsProvider()
    val rotationViewModel =
        viewModel<RotationViewModel>(
            factory =
                remember {
                    RotationViewModelFactory(
                        configProvider = { currentConfigProvider() },
                        rotationStatusProvider = { currentRotationStatusProvider() },
                        currentPublicIpProvider = { currentCurrentPublicIpProvider() },
                        rootAvailabilityProvider = { currentRootAvailabilityProvider() },
                        cooldownRemainingSecondsProvider = { currentCooldownRemainingSecondsProvider() },
                        activeConnectionsProvider = { currentActiveConnectionsProvider() },
                        redactionSecretsProvider = { currentRedactionSecretsProvider() },
                        auditOccurredAtEpochMillisProvider = { currentAuditOccurredAtEpochMillisProvider() },
                        actionHandler = { action ->
                            when (action) {
                                RotationScreenAction.CheckRoot -> currentOnCheckRoot()
                                RotationScreenAction.ProbeCurrentPublicIp -> currentOnProbeCurrentPublicIp()
                                RotationScreenAction.RotateMobileData -> currentOnRotateMobileData()
                                RotationScreenAction.RotateAirplaneMode -> currentOnRotateAirplaneMode()
                                RotationScreenAction.CopyDiagnostics -> Unit
                            }
                        },
                    )
                },
        )
    val screenState by rotationViewModel.state.collectAsStateWithLifecycle()
    val dispatchEvent: (RotationScreenEvent) -> Unit = { event ->
        rotationViewModel.handle(event)
        rotationViewModel.consumeEffects().forEach { effect ->
            when (effect) {
                is RotationScreenEffect.CopyText -> onCopyRotationDiagnosticsText(effect.text)
                is RotationScreenEffect.RecordAuditAction -> onRecordRotationAuditAction(effect.record)
            }
        }
    }
    LaunchedEffect(Unit) {
        dispatchEvent(RotationScreenEvent.Refresh)
    }
    LaunchedEffect(
        observedConfig,
        observedRotationStatus,
        observedCurrentPublicIp,
        observedRootAvailability,
        observedCooldownRemainingSeconds,
        observedActiveConnections,
        observedRedactionSecrets,
    ) {
        rotationViewModel.handle(RotationScreenEvent.Refresh)
    }

    CellularProxyRotationScreen(
        state = screenState,
        actionsEnabled = true,
        onCheckRoot = { dispatchEvent(RotationScreenEvent.CheckRoot) },
        onProbeCurrentPublicIp = { dispatchEvent(RotationScreenEvent.ProbeCurrentPublicIp) },
        onRotateMobileData = { dispatchEvent(RotationScreenEvent.RotateMobileData) },
        onRotateAirplaneMode = { dispatchEvent(RotationScreenEvent.RotateAirplaneMode) },
        onCopyDiagnostics = { dispatchEvent(RotationScreenEvent.CopyDiagnostics) },
    )
}

private class RotationViewModelFactory(
    private val configProvider: () -> AppConfig,
    private val rotationStatusProvider: () -> RotationStatus,
    private val currentPublicIpProvider: () -> String?,
    private val rootAvailabilityProvider: () -> RootAvailabilityStatus,
    private val cooldownRemainingSecondsProvider: () -> Long?,
    private val activeConnectionsProvider: () -> Long,
    private val redactionSecretsProvider: () -> LogRedactionSecrets,
    private val auditOccurredAtEpochMillisProvider: () -> Long,
    private val actionHandler: (RotationScreenAction) -> Unit,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RotationViewModel(
        configProvider = configProvider,
        rotationStatusProvider = rotationStatusProvider,
        currentPublicIpProvider = currentPublicIpProvider,
        rootAvailabilityProvider = rootAvailabilityProvider,
        cooldownRemainingSecondsProvider = cooldownRemainingSecondsProvider,
        activeConnectionsProvider = activeConnectionsProvider,
        secretsProvider = redactionSecretsProvider,
        auditActionsEnabled = true,
        auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
        actionHandler = actionHandler,
    ) as T
}

@Composable
internal fun CellularProxyRotationScreen(
    state: RotationScreenState =
        RotationScreenState.from(
            config = AppConfig.default(),
            rotationStatus = RotationStatus.idle(),
            rootAvailability = RootAvailabilityStatus.Unknown,
        ),
    actionsEnabled: Boolean = false,
    onCheckRoot: () -> Unit = {},
    onProbeCurrentPublicIp: () -> Unit = {},
    onRotateMobileData: () -> Unit = {},
    onRotateAirplaneMode: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
) {
    var pendingConfirmationAction by remember { mutableStateOf<RotationScreenAction?>(null) }

    fun performAction(action: RotationScreenAction) {
        when (action) {
            RotationScreenAction.CheckRoot -> onCheckRoot()
            RotationScreenAction.ProbeCurrentPublicIp -> onProbeCurrentPublicIp()
            RotationScreenAction.RotateMobileData -> onRotateMobileData()
            RotationScreenAction.RotateAirplaneMode -> onRotateAirplaneMode()
            RotationScreenAction.CopyDiagnostics -> onCopyDiagnostics()
        }
    }

    fun requestAction(action: RotationScreenAction) {
        when (rotationActionDispatchMode(action)) {
            RotationActionDispatchMode.Immediate -> performAction(action)
            RotationActionDispatchMode.ConfirmFirst -> pendingConfirmationAction = action
        }
    }

    pendingConfirmationAction?.let { action ->
        val canConfirm =
            rotationActionCanDispatch(
                action = action,
                actionsEnabled = actionsEnabled,
                availableActions = state.availableActions,
            )
        AlertDialog(
            onDismissRequest = {
                pendingConfirmationAction = null
            },
            title = {
                Text(action.confirmationTitle ?: "Confirm rotation action")
            },
            text = {
                Text("Confirm this high-impact root rotation action.")
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
            text = "Rotation",
            style = MaterialTheme.typography.headlineSmall,
        )

        RotationActionRow(
            actionsEnabled = actionsEnabled,
            availableActions = state.availableActions,
            onAction = ::requestAction,
        )

        RotationSection("Root And Cooldown") {
            RotationField("Root availability", state.rootAvailability)
            RotationField("Root operations", state.rootOperations)
            RotationField("Cooldown status", state.cooldownStatus)
            RotationField("Strict IP change", state.strictIpChange)
        }

        RotationSection("Progress") {
            RotationField("Last rotation result", state.lastRotationResult)
            RotationField("Current phase", state.currentPhase)
            RotationField("Pause/drain status", state.pauseDrainStatus)
            RotationField("Pending operation", state.pendingOperation)
        }

        RotationSection("Warnings") {
            if (state.warnings.isEmpty()) {
                RotationField("Current warnings", "None")
            } else {
                state.warnings.forEach { warning ->
                    RotationField("Current warning", warning.label)
                }
            }
        }

        RotationSection("Public IP") {
            RotationField("Current public IP", state.currentPublicIp)
            RotationField("Old public IP", state.oldPublicIp)
            RotationField("New public IP", state.newPublicIp)
        }
    }
}

internal data class RotationScreenState(
    val rootAvailability: String,
    val rootOperations: String,
    val cooldownStatus: String,
    val lastRotationResult: String,
    val currentPublicIp: String,
    val oldPublicIp: String,
    val newPublicIp: String,
    val currentPhase: String,
    val pauseDrainStatus: String,
    val pendingOperation: String,
    val strictIpChange: String,
    val warnings: Set<RotationScreenWarning>,
    val copyableDiagnostics: String,
    val availableActions: List<RotationScreenAction>,
) {
    companion object {
        fun from(
            config: AppConfig,
            rotationStatus: RotationStatus,
            rootAvailability: RootAvailabilityStatus,
            currentPublicIp: String? = null,
            cooldownRemainingSeconds: Long? = null,
            activeConnections: Long = 0,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): RotationScreenState {
            val rootAvailabilityText = rootAvailability.name
            val rootOperations = if (config.root.operationsEnabled) "Enabled" else "Disabled"
            val cooldownStatus = cooldownRemainingSeconds.toCooldownText()
            val lastRotationResult = rotationStatus.toRotationResultText()
            val currentPublicIp = currentPublicIp?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val oldPublicIp = rotationStatus.oldPublicIp?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val newPublicIp = rotationStatus.newPublicIp?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val currentPhase = rotationStatus.state.name
            val pauseDrainStatus = rotationStatus.toPauseDrainText(activeConnections)
            val pendingOperation = "None"
            val strictIpChange = if (config.rotation.strictIpChangeRequired) "Required" else "Not required"
            val warnings =
                rotationScreenWarnings(
                    config = config,
                    rotationStatus = rotationStatus,
                    rootAvailability = rootAvailability,
                    cooldownRemainingSeconds = cooldownRemainingSeconds,
                )
            val warningsText = warnings.toWarningsText()
            return RotationScreenState(
                rootAvailability = rootAvailabilityText,
                rootOperations = rootOperations,
                cooldownStatus = cooldownStatus,
                lastRotationResult = lastRotationResult,
                currentPublicIp = currentPublicIp,
                oldPublicIp = oldPublicIp,
                newPublicIp = newPublicIp,
                currentPhase = currentPhase,
                pauseDrainStatus = pauseDrainStatus,
                pendingOperation = pendingOperation,
                strictIpChange = strictIpChange,
                warnings = warnings,
                copyableDiagnostics =
                    listOf(
                        "Root availability: $rootAvailabilityText",
                        "Root operations: $rootOperations",
                        "Cooldown status: $cooldownStatus",
                        "Last rotation result: $lastRotationResult",
                        "Current public IP: $currentPublicIp",
                        "Old public IP: $oldPublicIp",
                        "New public IP: $newPublicIp",
                        "Current phase: $currentPhase",
                        "Pause/drain status: $pauseDrainStatus",
                        "Pending operation: $pendingOperation",
                        "Strict IP change: $strictIpChange",
                        "Warnings: $warningsText",
                    ).joinToString(separator = "\n"),
                availableActions =
                    rotationScreenActions(
                        config = config,
                        rotationStatus = rotationStatus,
                        rootAvailability = rootAvailability,
                        cooldownRemainingSeconds = cooldownRemainingSeconds,
                    ),
            )
        }
    }
}

internal enum class RotationScreenWarning(
    val label: String,
) {
    RootUnavailable("Root access unavailable"),
    RotationCooldownActive("Rotation blocked by cooldown"),
    RotationInProgress("Rotation already in progress"),
    OperationInProgress("Rotation operation in progress"),
}

internal enum class RotationScreenAction(
    val confirmationTitle: String? = null,
) {
    CheckRoot,
    ProbeCurrentPublicIp,
    RotateMobileData(
        confirmationTitle = "Confirm mobile data rotation",
    ),
    RotateAirplaneMode(
        confirmationTitle = "Confirm airplane mode rotation",
    ),
    CopyDiagnostics,
    ;

    val requiresConfirmation: Boolean = confirmationTitle != null
}

internal enum class RotationActionDispatchMode {
    Immediate,
    ConfirmFirst,
}

internal fun rotationActionDispatchMode(action: RotationScreenAction): RotationActionDispatchMode = if (action.requiresConfirmation) {
    RotationActionDispatchMode.ConfirmFirst
} else {
    RotationActionDispatchMode.Immediate
}

internal fun rotationActionCanDispatch(
    action: RotationScreenAction,
    actionsEnabled: Boolean,
    availableActions: List<RotationScreenAction>,
): Boolean = actionsEnabled && action in availableActions

internal class RotationScreenController(
    private val configProvider: () -> AppConfig = { AppConfig.default() },
    private val rotationStatusProvider: () -> RotationStatus = { RotationStatus.idle() },
    private val currentPublicIpProvider: () -> String? = { null },
    private val rootAvailabilityProvider: () -> RootAvailabilityStatus = { RootAvailabilityStatus.Unknown },
    private val cooldownRemainingSecondsProvider: () -> Long? = { null },
    private val activeConnectionsProvider: () -> Long = { 0 },
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
    private val auditActionsEnabled: Boolean = false,
    private val auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
    private val actionHandler: (RotationScreenAction) -> Unit = {},
) {
    private var lastObservedRotationStatus: RotationStatus = rotationStatusProvider()
    private var lastObservedRootAvailability: RootAvailabilityStatus = rootAvailabilityProvider()
    private var lastObservedCurrentPublicIp: String? = currentPublicIpProvider()
    private val pendingActions = mutableSetOf<RotationScreenAction>()
    private val pendingEffects = mutableListOf<RotationScreenEffect>()
    var state: RotationScreenState = buildState()
        private set

    fun handle(event: RotationScreenEvent) {
        refreshPendingActions()
        when (event) {
            RotationScreenEvent.CopyDiagnostics -> {
                if (RotationScreenAction.CopyDiagnostics in state.availableActions) {
                    recordAuditAction(RotationScreenAction.CopyDiagnostics)?.let(pendingEffects::add)
                    pendingEffects.add(RotationScreenEffect.CopyText(state.copyableDiagnostics))
                }
            }
            RotationScreenEvent.CheckRoot -> dispatchAction(RotationScreenAction.CheckRoot)
            RotationScreenEvent.ProbeCurrentPublicIp -> dispatchAction(RotationScreenAction.ProbeCurrentPublicIp)
            RotationScreenEvent.RotateMobileData -> dispatchAction(RotationScreenAction.RotateMobileData)
            RotationScreenEvent.RotateAirplaneMode -> dispatchAction(RotationScreenAction.RotateAirplaneMode)
            RotationScreenEvent.Refresh -> state = buildState()
        }
    }

    fun consumeEffects(): List<RotationScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun dispatchAction(action: RotationScreenAction) {
        if (action !in state.availableActions) {
            return
        }
        if (action.tracksPendingOperation) {
            pendingActions.add(action)
        }
        recordAuditAction(action)?.let(pendingEffects::add)
        actionHandler(action)
        state = buildState()
    }

    private fun refreshPendingActions() {
        val currentStatus = rotationStatusProvider()
        val currentRootAvailability = rootAvailabilityProvider()
        val currentPublicIp = currentPublicIpProvider()
        if (currentStatus != lastObservedRotationStatus && !currentStatus.isActive) {
            pendingActions.removeAll(rotationScreenRotationActions)
        }
        if (currentRootAvailability != lastObservedRootAvailability) {
            pendingActions.remove(RotationScreenAction.CheckRoot)
        }
        if (currentPublicIp != lastObservedCurrentPublicIp) {
            pendingActions.remove(RotationScreenAction.ProbeCurrentPublicIp)
        }
        lastObservedRotationStatus = currentStatus
        lastObservedRootAvailability = currentRootAvailability
        lastObservedCurrentPublicIp = currentPublicIp
        state = buildState()
    }

    private fun buildState(): RotationScreenState {
        val rotationStatus = rotationStatusProvider()
        return RotationScreenState
            .from(
                config = configProvider(),
                rotationStatus = rotationStatus,
                rootAvailability = rootAvailabilityProvider(),
                currentPublicIp = currentPublicIpProvider(),
                cooldownRemainingSeconds = cooldownRemainingSecondsProvider(),
                activeConnections = activeConnectionsProvider(),
                secrets = secretsProvider(),
            ).withoutPendingActions(pendingActions)
    }

    private fun recordAuditAction(action: RotationScreenAction): RotationScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        RotationScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.Rotation,
                severity = LogsAuditRecordSeverity.Info,
                title = "Rotation ${action.auditName}",
                detail = "action=${action.auditName} phase=${rotationStatusProvider().state.name}",
            ),
        )
    } else {
        null
    }
}

internal sealed interface RotationScreenEvent {
    data object CheckRoot : RotationScreenEvent

    data object ProbeCurrentPublicIp : RotationScreenEvent

    data object RotateMobileData : RotationScreenEvent

    data object RotateAirplaneMode : RotationScreenEvent

    data object CopyDiagnostics : RotationScreenEvent

    data object Refresh : RotationScreenEvent
}

internal sealed interface RotationScreenEffect {
    data class CopyText(
        val text: String,
    ) : RotationScreenEffect

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
    ) : RotationScreenEffect
}

private val RotationScreenAction.auditName: String
    get() =
        when (this) {
            RotationScreenAction.CheckRoot -> "check_root"
            RotationScreenAction.ProbeCurrentPublicIp -> "probe_current_public_ip"
            RotationScreenAction.RotateMobileData -> "rotate_mobile_data"
            RotationScreenAction.RotateAirplaneMode -> "rotate_airplane_mode"
            RotationScreenAction.CopyDiagnostics -> "copy_diagnostics"
        }

private fun RotationScreenState.withoutPendingActions(
    pendingActions: Set<RotationScreenAction>,
): RotationScreenState {
    val pendingOperation = pendingActions.pendingOperationLabel()
    val warnings =
        if (
            pendingActions.isNotEmpty() &&
            RotationScreenWarning.RotationInProgress !in warnings
        ) {
            warnings + RotationScreenWarning.OperationInProgress
        } else {
            warnings
        }
    return copy(
        pendingOperation = pendingOperation,
        warnings = warnings,
        copyableDiagnostics =
            copyableDiagnosticsWithPendingOperation(
                pendingOperation = pendingOperation,
                warnings = warnings,
            ),
        availableActions = availableActions.filterNot(pendingActions.suppressedActionFilter()::contains),
    )
}

private fun Set<RotationScreenAction>.suppressedActionFilter(): Set<RotationScreenAction> {
    if (none(rotationScreenRotationActions::contains)) {
        return this
    }
    return this + rotationScreenRotationActions
}

private val RotationScreenAction.tracksPendingOperation: Boolean
    get() = this != RotationScreenAction.CopyDiagnostics

private val rotationScreenRotationActions: Set<RotationScreenAction> =
    setOf(
        RotationScreenAction.RotateMobileData,
        RotationScreenAction.RotateAirplaneMode,
    )

private fun Set<RotationScreenAction>.pendingOperationLabel(): String = firstOrNull()?.let { action ->
    "In progress: ${action.toButtonLabel()}"
} ?: "None"

private fun RotationScreenState.copyableDiagnosticsWithPendingOperation(
    pendingOperation: String,
    warnings: Set<RotationScreenWarning>,
): String = listOf(
    "Root availability: $rootAvailability",
    "Root operations: $rootOperations",
    "Cooldown status: $cooldownStatus",
    "Last rotation result: $lastRotationResult",
    "Current public IP: $currentPublicIp",
    "Old public IP: $oldPublicIp",
    "New public IP: $newPublicIp",
    "Current phase: $currentPhase",
    "Pause/drain status: $pauseDrainStatus",
    "Pending operation: $pendingOperation",
    "Strict IP change: $strictIpChange",
    "Warnings: ${warnings.toWarningsText()}",
).joinToString(separator = "\n")

private fun rotationScreenWarnings(
    config: AppConfig,
    rotationStatus: RotationStatus,
    rootAvailability: RootAvailabilityStatus,
    cooldownRemainingSeconds: Long?,
): Set<RotationScreenWarning> = buildSet {
    if (config.root.operationsEnabled && rootAvailability == RootAvailabilityStatus.Unavailable) {
        add(RotationScreenWarning.RootUnavailable)
    }
    if (cooldownRemainingSeconds != null && cooldownRemainingSeconds > 0) {
        add(RotationScreenWarning.RotationCooldownActive)
    }
    if (rotationStatus.isActive) {
        add(RotationScreenWarning.RotationInProgress)
    }
}

private fun Set<RotationScreenWarning>.toWarningsText(): String = if (isEmpty()) {
    "None"
} else {
    joinToString(separator = " | ") { warning -> warning.label }
}

private fun RotationScreenAction.toButtonLabel(): String = when (this) {
    RotationScreenAction.CheckRoot -> "Check root"
    RotationScreenAction.ProbeCurrentPublicIp -> "Probe current public IP"
    RotationScreenAction.RotateMobileData -> "Rotate mobile data"
    RotationScreenAction.RotateAirplaneMode -> "Rotate airplane mode"
    RotationScreenAction.CopyDiagnostics -> "Copy diagnostics"
}

@Composable
private fun RotationActionRow(
    actionsEnabled: Boolean,
    availableActions: List<RotationScreenAction>,
    onAction: (RotationScreenAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                onAction(RotationScreenAction.CheckRoot)
            },
            enabled =
                rotationActionCanDispatch(
                    action = RotationScreenAction.CheckRoot,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check root")
        }
        OutlinedButton(
            onClick = {
                onAction(RotationScreenAction.ProbeCurrentPublicIp)
            },
            enabled =
                rotationActionCanDispatch(
                    action = RotationScreenAction.ProbeCurrentPublicIp,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Probe current public IP")
        }
        Button(
            onClick = {
                onAction(RotationScreenAction.RotateMobileData)
            },
            enabled =
                rotationActionCanDispatch(
                    action = RotationScreenAction.RotateMobileData,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Rotate mobile data")
        }
        OutlinedButton(
            onClick = {
                onAction(RotationScreenAction.RotateAirplaneMode)
            },
            enabled =
                rotationActionCanDispatch(
                    action = RotationScreenAction.RotateAirplaneMode,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Rotate airplane mode")
        }
        OutlinedButton(
            onClick = {
                onAction(RotationScreenAction.CopyDiagnostics)
            },
            enabled =
                rotationActionCanDispatch(
                    action = RotationScreenAction.CopyDiagnostics,
                    actionsEnabled = actionsEnabled,
                    availableActions = availableActions,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy diagnostics")
        }
    }
}

@Composable
private fun RotationSection(
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
private fun RotationField(
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

private fun Long?.toCooldownText(): String = when {
    this == null -> "Ready or unknown"
    this <= 0 -> "Ready"
    else -> "$this seconds remaining"
}

private fun RotationStatus.toRotationResultText(): String = when (state) {
    RotationState.Idle -> "No rotation run"
    RotationState.Completed -> "Completed: ${operation?.name ?: "Unknown"}"
    RotationState.Failed -> "Failed: ${failureReason?.name ?: "Unknown"}"
    else -> "In progress: ${operation?.name ?: "Unknown"}"
}

private fun RotationStatus.toPauseDrainText(activeConnections: Long): String = when (state) {
    RotationState.PausingNewRequests -> "Pausing new proxy requests"
    RotationState.DrainingConnections -> "$activeConnections active connections draining"
    RotationState.ResumingProxyRequests -> "Resuming proxy requests"
    else -> "Not active"
}

private fun rotationScreenActions(
    config: AppConfig,
    rotationStatus: RotationStatus,
    rootAvailability: RootAvailabilityStatus,
    cooldownRemainingSeconds: Long?,
): List<RotationScreenAction> = buildList {
    add(RotationScreenAction.CheckRoot)
    add(RotationScreenAction.ProbeCurrentPublicIp)
    if (
        config.root.operationsEnabled &&
        rootAvailability == RootAvailabilityStatus.Available &&
        !rotationStatus.isActive &&
        (cooldownRemainingSeconds == null || cooldownRemainingSeconds <= 0)
    ) {
        add(RotationScreenAction.RotateMobileData)
        add(RotationScreenAction.RotateAirplaneMode)
    }
    add(RotationScreenAction.CopyDiagnostics)
}
