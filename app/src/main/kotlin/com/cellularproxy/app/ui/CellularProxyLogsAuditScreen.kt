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
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor

@Composable
internal fun CellularProxyLogsAuditScreen(
    state: LogsAuditScreenState = LogsAuditScreenState.from(),
    actionsEnabled: Boolean = false,
    onCopySelectedRecord: () -> Unit = {},
    onCopyFilteredSummary: () -> Unit = {},
    onExportRedactedBundle: () -> Unit = {},
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
            text = "Logs/Audit",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = state.resultSummary,
            style = MaterialTheme.typography.titleMedium,
        )
        LogsAuditFilterSummary(state)
        LogsAuditActionRow(
            state = state,
            actionsEnabled = actionsEnabled,
            onCopySelectedRecord = onCopySelectedRecord,
            onCopyFilteredSummary = onCopyFilteredSummary,
            onExportRedactedBundle = onExportRedactedBundle,
        )
        if (state.rows.isEmpty()) {
            Text(
                text = "No log or audit records match the current filters.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.rows.forEach { row ->
                LogsAuditRow(row)
            }
        }
    }
}

internal class LogsAuditScreenState private constructor(
    val rows: List<LogsAuditScreenRow>,
    val selectedRow: LogsAuditScreenRow?,
    val filter: LogsAuditScreenFilter,
    val totalRowCount: Int,
    val exportSupported: Boolean,
    val searchDisplayText: String,
    val copyableSelectedRecord: String?,
    val copyableFilteredSummary: String,
    val exportBundle: LogsAuditScreenExportBundle?,
) {
    val resultSummary: String = "${rows.size} of $totalRowCount records"
    val availableActions: List<LogsAuditScreenAction> =
        buildList {
            if (selectedRow != null) {
                add(LogsAuditScreenAction.CopySelectedRecord)
            }
            if (rows.isNotEmpty()) {
                add(LogsAuditScreenAction.CopyFilteredSummary)
            }
            if (rows.isNotEmpty() && exportSupported) {
                add(LogsAuditScreenAction.ExportRedactedBundle)
            }
        }

    companion object {
        fun from(
            rows: List<LogsAuditScreenInputRow> = emptyList(),
            selectedRowId: String? = null,
            filter: LogsAuditScreenFilter = LogsAuditScreenFilter(),
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
            exportSupported: Boolean = false,
            exportGeneratedAtEpochMillis: Long = 0,
            maxRows: Int? = null,
        ): LogsAuditScreenState {
            require(exportGeneratedAtEpochMillis >= 0) { "Export timestamp must be non-negative." }
            require(maxRows == null || maxRows > 0) { "Maximum row count must be positive." }
            val filteredRows =
                rows
                    .map { row -> row.toScreenRow(secrets) }
                    .filter { row -> filter.matches(row) }
                    .sortedByDescending(LogsAuditScreenRow::occurredAtEpochMillis)
                    .let { sortedRows -> maxRows?.let(sortedRows::take) ?: sortedRows }
            val copyableFilteredSummary =
                filteredRows.joinToString(
                    separator = "\n\n",
                    transform = LogsAuditScreenRow::copyText,
                )
            val selectedRow = filteredRows.firstOrNull { row -> row.id == selectedRowId }
            return LogsAuditScreenState(
                rows = filteredRows,
                selectedRow = selectedRow,
                filter = filter,
                totalRowCount = rows.size,
                exportSupported = exportSupported,
                searchDisplayText = LogRedactor.redact(filter.search, secrets),
                copyableSelectedRecord = selectedRow?.copyText,
                copyableFilteredSummary = copyableFilteredSummary,
                exportBundle =
                    if (exportSupported && filteredRows.isNotEmpty()) {
                        LogsAuditScreenExportBundle.from(
                            generatedAtEpochMillis = exportGeneratedAtEpochMillis,
                            rows = filteredRows,
                        )
                    } else {
                        null
                    },
            )
        }
    }
}

internal data class LogsAuditScreenInputRow(
    val id: String,
    val category: LogsAuditScreenCategory,
    val severity: LogsAuditScreenSeverity,
    val occurredAtEpochMillis: Long,
    val title: String,
    val detail: String,
) {
    init {
        require(id.isNotBlank()) { "Log row id must not be blank." }
        require(occurredAtEpochMillis >= 0) { "Log row timestamp must be non-negative." }
    }
}

internal data class LogsAuditScreenRow(
    val id: String,
    val category: LogsAuditScreenCategory,
    val severity: LogsAuditScreenSeverity,
    val occurredAtEpochMillis: Long,
    val title: String,
    val detail: String,
) {
    val copyText: String = "${category.label} | ${severity.label} | $occurredAtEpochMillis | $title\n$detail"
}

internal data class LogsAuditScreenExportBundle(
    val fileName: String,
    val mediaType: String,
    val generatedAtEpochMillis: Long,
    val rowCount: Int,
    val text: String,
) {
    companion object {
        fun from(
            generatedAtEpochMillis: Long,
            rows: List<LogsAuditScreenRow>,
        ): LogsAuditScreenExportBundle {
            require(generatedAtEpochMillis >= 0) { "Export timestamp must be non-negative." }
            val body = rows.joinToString(separator = "\n\n", transform = LogsAuditScreenRow::copyText)
            return LogsAuditScreenExportBundle(
                fileName = "cellularproxy-logs-audit-$generatedAtEpochMillis.txt",
                mediaType = "text/plain",
                generatedAtEpochMillis = generatedAtEpochMillis,
                rowCount = rows.size,
                text =
                    listOf(
                        "CellularProxy Logs/Audit Export",
                        "Generated: $generatedAtEpochMillis",
                        "Rows: ${rows.size}",
                        "",
                        body,
                    ).joinToString(separator = "\n"),
            )
        }
    }
}

internal data class LogsAuditScreenFilter(
    val category: LogsAuditScreenCategory? = null,
    val severity: LogsAuditScreenSeverity? = null,
    val fromEpochMillis: Long? = null,
    val toEpochMillis: Long? = null,
    val search: String = "",
) {
    init {
        require(fromEpochMillis == null || fromEpochMillis >= 0) { "From timestamp must be non-negative." }
        require(toEpochMillis == null || toEpochMillis >= 0) { "To timestamp must be non-negative." }
        require(
            fromEpochMillis == null ||
                toEpochMillis == null ||
                fromEpochMillis <= toEpochMillis,
        ) { "From timestamp must be before or equal to to timestamp." }
    }

    fun matches(row: LogsAuditScreenRow): Boolean {
        val normalizedSearch = search.trim().lowercase()
        val searchableText = "${row.category.label} ${row.severity.label} ${row.title} ${row.detail}".lowercase()
        return (category == null || row.category == category) &&
            (severity == null || row.severity == severity) &&
            (fromEpochMillis == null || row.occurredAtEpochMillis >= fromEpochMillis) &&
            (toEpochMillis == null || row.occurredAtEpochMillis <= toEpochMillis) &&
            (normalizedSearch.isBlank() || searchableText.contains(normalizedSearch))
    }
}

internal enum class LogsAuditScreenCategory(
    val label: String,
) {
    AppRuntime("App runtime"),
    ProxyServer("Proxy server"),
    ManagementApi("Management API"),
    CloudflareTunnel("Cloudflare tunnel"),
    RootCommands("Root commands"),
    Rotation("Rotation"),
    Audit("Audit records"),
}

internal enum class LogsAuditScreenSeverity(
    val label: String,
) {
    Info("Info"),
    Warning("Warning"),
    Failed("Failed"),
}

internal enum class LogsAuditScreenAction {
    CopySelectedRecord,
    CopyFilteredSummary,
    ExportRedactedBundle,
}

@Composable
private fun LogsAuditFilterSummary(state: LogsAuditScreenState) {
    val filter = state.filter
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LogsAuditField("Category", filter.category?.label ?: "All")
        LogsAuditField("Severity", filter.severity?.label ?: "All")
        LogsAuditField("Time window", filter.timeWindowText())
        LogsAuditField("Search", state.searchDisplayText.ifBlank { "None" })
    }
}

@Composable
private fun LogsAuditActionRow(
    state: LogsAuditScreenState,
    actionsEnabled: Boolean,
    onCopySelectedRecord: () -> Unit,
    onCopyFilteredSummary: () -> Unit,
    onExportRedactedBundle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onCopySelectedRecord,
            enabled = actionsEnabled && LogsAuditScreenAction.CopySelectedRecord in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy selected record")
        }
        OutlinedButton(
            onClick = onCopyFilteredSummary,
            enabled = actionsEnabled && LogsAuditScreenAction.CopyFilteredSummary in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy filtered summary")
        }
        OutlinedButton(
            onClick = onExportRedactedBundle,
            enabled = actionsEnabled && LogsAuditScreenAction.ExportRedactedBundle in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export redacted bundle")
        }
    }
}

@Composable
private fun LogsAuditRow(row: LogsAuditScreenRow) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = row.category.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = row.severity.label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LogsAuditField("Occurred", row.occurredAtEpochMillis.toString())
        LogsAuditField("Title", row.title)
        LogsAuditField("Details", row.detail)
    }
}

@Composable
private fun LogsAuditField(
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

private fun LogsAuditScreenInputRow.toScreenRow(secrets: LogRedactionSecrets): LogsAuditScreenRow = LogsAuditScreenRow(
    id = id,
    category = category,
    severity = severity,
    occurredAtEpochMillis = occurredAtEpochMillis,
    title = LogRedactor.redact(title, secrets),
    detail = LogRedactor.redact(detail, secrets),
)

private fun LogsAuditScreenFilter.timeWindowText(): String = when {
    fromEpochMillis == null && toEpochMillis == null -> "All"
    fromEpochMillis != null && toEpochMillis != null -> "$fromEpochMillis to $toEpochMillis"
    fromEpochMillis != null -> "From $fromEpochMillis"
    else -> "Until $toEpochMillis"
}
