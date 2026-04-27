@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultItem
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticsResultModel
import com.cellularproxy.shared.logging.LogRedactor

@Composable
internal fun CellularProxyDiagnosticsScreen(
    state: DiagnosticsScreenState = DiagnosticsScreenState.from(DiagnosticsResultModel.empty()),
    actionsEnabled: Boolean = false,
    onRunAllChecks: () -> Unit = {},
    onRunCheck: (DiagnosticCheckType) -> Unit = {},
    onCopySummary: () -> Unit = {},
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
            text = "Diagnostics",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = state.overallStatus,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = state.completionSummary,
            style = MaterialTheme.typography.bodyMedium,
        )
        DiagnosticsActionRow(
            state = state,
            actionsEnabled = actionsEnabled,
            onRunAllChecks = onRunAllChecks,
            onCopySummary = onCopySummary,
        )
        state.items.forEach { item ->
            DiagnosticsCheckRow(
                item = item,
                actionsEnabled = actionsEnabled,
                onRunCheck = onRunCheck,
            )
        }
    }
}

internal data class DiagnosticsScreenState(
    val overallStatus: String,
    val completionSummary: String,
    val items: List<DiagnosticsScreenItem>,
    val copyableSummary: String,
    val availableActions: List<DiagnosticsScreenAction>,
) {
    companion object {
        fun from(model: DiagnosticsResultModel): DiagnosticsScreenState {
            val completedCount = model.results.count { it.status.isCompleted }
            val anyRunning = model.results.any { it.status == DiagnosticResultStatus.Running }
            val overallStatus =
                when {
                    model.results.any { it.status == DiagnosticResultStatus.Failed } -> DiagnosticResultStatus.Failed.label
                    model.results.any { it.status == DiagnosticResultStatus.Warning } -> DiagnosticResultStatus.Warning.label
                    model.results.any { it.status == DiagnosticResultStatus.Running } -> DiagnosticResultStatus.Running.label
                    completedCount == model.results.size -> DiagnosticResultStatus.Passed.label
                    else -> DiagnosticResultStatus.NotRun.label
                }
            val items = model.results.map(DiagnosticResultItem::toScreenItem)
            return DiagnosticsScreenState(
                overallStatus = overallStatus,
                completionSummary = "$completedCount of ${model.results.size} checks complete",
                items = items,
                copyableSummary = items.joinToString(separator = "\n", transform = DiagnosticsScreenItem::summaryLine),
                availableActions =
                    buildList {
                        if (!anyRunning) {
                            add(DiagnosticsScreenAction.RunAllChecks)
                        }
                        if (completedCount > 0) {
                            add(DiagnosticsScreenAction.CopySummary)
                        }
                    },
            )
        }
    }
}

internal data class DiagnosticsScreenItem(
    val type: DiagnosticCheckType,
    val label: String,
    val status: String,
    val duration: String,
    val errorCategory: String,
    val details: String,
    val availableActions: List<DiagnosticsScreenAction>,
) {
    fun summaryLine(): String {
        val metadata =
            listOfNotNull(
                duration
                    .takeIf { it != "Not run" }
                    ?.removeSuffix(" ms")
                    ?.let { "in ${it}ms" },
                errorCategory.takeIf { it != "None" }?.let { "($it)" },
            ).joinToString(separator = " ")
        val detailSuffix = details.takeIf { it != "None" }?.let { " - $it" }.orEmpty()
        return listOf(label, listOf(status, metadata).filter(String::isNotBlank).joinToString(separator = " "))
            .joinToString(separator = ": ") + detailSuffix
    }
}

internal enum class DiagnosticsScreenAction {
    RunAllChecks,
    RunCheck,
    CopySummary,
}

private val DiagnosticResultStatus.isCompleted: Boolean
    get() = this != DiagnosticResultStatus.NotRun && this != DiagnosticResultStatus.Running

@Composable
private fun DiagnosticsActionRow(
    state: DiagnosticsScreenState,
    actionsEnabled: Boolean,
    onRunAllChecks: () -> Unit,
    onCopySummary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onRunAllChecks,
            enabled = actionsEnabled && DiagnosticsScreenAction.RunAllChecks in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run all checks")
        }
        OutlinedButton(
            onClick = onCopySummary,
            enabled = actionsEnabled && DiagnosticsScreenAction.CopySummary in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy summary")
        }
    }
}

@Composable
private fun DiagnosticsCheckRow(
    item: DiagnosticsScreenItem,
    actionsEnabled: Boolean,
    onRunCheck: (DiagnosticCheckType) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = item.status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        DiagnosticsField("Duration", item.duration)
        DiagnosticsField("Error category", item.errorCategory)
        DiagnosticsField("Details", item.details)
        OutlinedButton(
            onClick = { onRunCheck(item.type) },
            enabled = actionsEnabled && DiagnosticsScreenAction.RunCheck in item.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run ${item.label}")
        }
    }
}

@Composable
private fun DiagnosticsField(
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

private fun DiagnosticResultItem.toScreenItem(): DiagnosticsScreenItem = DiagnosticsScreenItem(
    type = type,
    label = label,
    status = status.label,
    duration = durationMillis?.let { "$it ms" } ?: "Not run",
    errorCategory = errorCategory?.let(LogRedactor::redact) ?: "None",
    details = details?.let(LogRedactor::redact) ?: "None",
    availableActions =
        if (status == DiagnosticResultStatus.Running) {
            emptyList()
        } else {
            listOf(DiagnosticsScreenAction.RunCheck)
        },
)
