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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus

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
        }

        RotationSection("Public IP") {
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
    val oldPublicIp: String,
    val newPublicIp: String,
    val currentPhase: String,
    val pauseDrainStatus: String,
    val strictIpChange: String,
    val copyableDiagnostics: String,
    val availableActions: List<RotationScreenAction>,
) {
    companion object {
        fun from(
            config: AppConfig,
            rotationStatus: RotationStatus,
            rootAvailability: RootAvailabilityStatus,
            cooldownRemainingSeconds: Long? = null,
            activeConnections: Long = 0,
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): RotationScreenState {
            val rootAvailabilityText = rootAvailability.name
            val rootOperations = if (config.root.operationsEnabled) "Enabled" else "Disabled"
            val cooldownStatus = cooldownRemainingSeconds.toCooldownText()
            val lastRotationResult = rotationStatus.toRotationResultText()
            val oldPublicIp = rotationStatus.oldPublicIp?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val newPublicIp = rotationStatus.newPublicIp?.let { LogRedactor.redact(it, secrets) } ?: "Unavailable"
            val currentPhase = rotationStatus.state.name
            val pauseDrainStatus = rotationStatus.toPauseDrainText(activeConnections)
            val strictIpChange = if (config.rotation.strictIpChangeRequired) "Required" else "Not required"
            return RotationScreenState(
                rootAvailability = rootAvailabilityText,
                rootOperations = rootOperations,
                cooldownStatus = cooldownStatus,
                lastRotationResult = lastRotationResult,
                oldPublicIp = oldPublicIp,
                newPublicIp = newPublicIp,
                currentPhase = currentPhase,
                pauseDrainStatus = pauseDrainStatus,
                strictIpChange = strictIpChange,
                copyableDiagnostics =
                    listOf(
                        "Root availability: $rootAvailabilityText",
                        "Root operations: $rootOperations",
                        "Cooldown status: $cooldownStatus",
                        "Last rotation result: $lastRotationResult",
                        "Old public IP: $oldPublicIp",
                        "New public IP: $newPublicIp",
                        "Current phase: $currentPhase",
                        "Pause/drain status: $pauseDrainStatus",
                        "Strict IP change: $strictIpChange",
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
