@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.PersistedForegroundServiceAuditRecord
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.audit.PersistedManagementApiAuditRecord
import com.cellularproxy.app.audit.PersistedRootCommandAuditRecord
import com.cellularproxy.app.service.ForegroundServiceAuditOutcome
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import com.cellularproxy.shared.root.RootCommandAuditPhase
import com.cellularproxy.shared.root.RootCommandOutcome

@Composable
internal fun CellularProxyLogsAuditRoute(
    logsAuditRowsProvider: () -> List<LogsAuditScreenInputRow> = { emptyList() },
    redactionSecretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    onCopyLogsAuditText: (String) -> Unit = {},
    onExportLogsAuditBundle: (LogsAuditScreenExportBundle) -> Unit = {},
    onRecordLogsAuditAction: (PersistedLogsAuditRecord) -> Unit = {},
) {
    val currentRowsProvider by rememberUpdatedState(logsAuditRowsProvider)
    val currentRedactionSecretsProvider by rememberUpdatedState(redactionSecretsProvider)
    val observedRows = logsAuditRowsProvider()
    val observedRedactionSecrets = redactionSecretsProvider()
    val controller =
        remember {
            LogsAuditScreenController(
                rowsProvider = { currentRowsProvider() },
                secretsProvider = { currentRedactionSecretsProvider() },
                exportSupported = true,
                exportGeneratedAtEpochMillisProvider = System::currentTimeMillis,
                auditOccurredAtEpochMillisProvider = System::currentTimeMillis,
                auditActionsEnabled = true,
                maxRows = LOGS_AUDIT_VISIBLE_ROW_LIMIT,
                loadInitialState = false,
            )
        }
    var screenState by remember { mutableStateOf(controller.state) }
    val dispatchEvent: (LogsAuditScreenEvent) -> Unit = { event ->
        controller.handle(event)
        controller.consumeEffects().forEach { effect ->
            when (effect) {
                is LogsAuditScreenEffect.CopyText -> onCopyLogsAuditText(effect.text)
                is LogsAuditScreenEffect.ExportBundle -> onExportLogsAuditBundle(effect.bundle)
                is LogsAuditScreenEffect.RecordAuditAction -> onRecordLogsAuditAction(effect.record)
            }
        }
        screenState = controller.state
    }
    LaunchedEffect(
        observedRows,
        observedRedactionSecrets,
    ) {
        controller.handle(LogsAuditScreenEvent.Refresh)
        screenState = controller.state
    }

    CellularProxyLogsAuditScreen(
        state = screenState,
        actionsEnabled = true,
        onSelectRecord = { rowId -> dispatchEvent(LogsAuditScreenEvent.SelectRecord(rowId)) },
        onClearSelection = { dispatchEvent(LogsAuditScreenEvent.ClearSelection) },
        onUpdateFilter = { filter -> dispatchEvent(LogsAuditScreenEvent.UpdateFilter(filter)) },
        onCopySelectedRecord = { dispatchEvent(LogsAuditScreenEvent.CopySelectedRecord) },
        onCopyFilteredSummary = { dispatchEvent(LogsAuditScreenEvent.CopyFilteredSummary) },
        onExportRedactedBundle = { dispatchEvent(LogsAuditScreenEvent.ExportRedactedBundle) },
    )
}

private const val LOGS_AUDIT_VISIBLE_ROW_LIMIT = 200

@Composable
internal fun CellularProxyLogsAuditScreen(
    state: LogsAuditScreenState = LogsAuditScreenState.from(),
    actionsEnabled: Boolean = false,
    onSelectRecord: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onUpdateFilter: (LogsAuditScreenFilter) -> Unit = {},
    onCopySelectedRecord: (String) -> Unit = {},
    onCopyFilteredSummary: (String) -> Unit = {},
    onExportRedactedBundle: (LogsAuditScreenExportBundle) -> Unit = {},
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
        LogsAuditSelectedRecord(
            selectedRow = state.selectedRow,
            actionsEnabled = actionsEnabled,
            onClearSelection = onClearSelection,
        )
        LogsAuditFilterSummary(state)
        LogsAuditCategoryFilter(
            state = state,
            onUpdateFilter = onUpdateFilter,
        )
        LogsAuditSeverityFilter(
            state = state,
            onUpdateFilter = onUpdateFilter,
        )
        LogsAuditTimeWindowFilter(
            state = state,
            onUpdateFilter = onUpdateFilter,
        )
        LogsAuditSearchFilter(
            state = state,
            onUpdateFilter = onUpdateFilter,
        )
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
                LogsAuditRow(
                    row = row,
                    actionsEnabled = actionsEnabled,
                    onSelectRecord = onSelectRecord,
                )
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
    private val appliedRowLimit: Int?,
) {
    val resultSummary: String =
        if (appliedRowLimit == null) {
            "${rows.size} of $totalRowCount records"
        } else {
            "${rows.size} of $totalRowCount records (limited to recent $appliedRowLimit)"
        }
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
            val redactedSearch = LogRedactor.redact(filter.search, secrets)
            val matchingFilter = filter.copy(search = redactedSearch)
            val sortedRows =
                rows
                    .map { row -> row.toScreenRow(secrets) }
                    .filter { row -> matchingFilter.matches(row) }
                    .sortedByDescending(LogsAuditScreenRow::occurredAtEpochMillis)
            val appliedRowLimit = maxRows?.takeIf { limit -> sortedRows.size > limit }
            val filteredRows = appliedRowLimit?.let(sortedRows::take) ?: sortedRows
            val copyableFilteredSummary =
                filteredRows.joinToString(
                    separator = "\n\n",
                    transform = LogsAuditScreenRow::copyText,
                )
            val selectedRow = filteredRows.firstOrNull { row -> row.id == selectedRowId }
            return LogsAuditScreenState(
                rows = filteredRows,
                selectedRow = selectedRow,
                filter = matchingFilter,
                totalRowCount = rows.size,
                exportSupported = exportSupported,
                searchDisplayText = redactedSearch,
                copyableSelectedRecord = selectedRow?.copyText,
                copyableFilteredSummary = copyableFilteredSummary,
                appliedRowLimit = appliedRowLimit,
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
        val normalizedSearch = search.normalizedLogsAuditSearchText()
        val searchableText =
            "${row.category.label} ${row.severity.label} ${row.title} ${row.detail}"
                .normalizedLogsAuditSearchText()
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

internal class LogsAuditScreenController(
    private val rows: List<LogsAuditScreenInputRow> = emptyList(),
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val rowsProvider: () -> List<LogsAuditScreenInputRow> = { rows },
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
    private val exportSupported: Boolean = false,
    private val exportGeneratedAtEpochMillis: Long = 0,
    private val exportGeneratedAtEpochMillisProvider: () -> Long = { exportGeneratedAtEpochMillis },
    private val auditOccurredAtEpochMillisProvider: () -> Long = { exportGeneratedAtEpochMillisProvider() },
    private val auditActionsEnabled: Boolean = false,
    private val maxRows: Int? = null,
    loadInitialState: Boolean = true,
) {
    private val pendingEffects = mutableListOf<LogsAuditScreenEffect>()
    var state: LogsAuditScreenState =
        if (loadInitialState) {
            buildState()
        } else {
            LogsAuditScreenState.from(
                exportSupported = exportSupported,
                exportGeneratedAtEpochMillis = exportGeneratedAtEpochMillis,
                maxRows = maxRows,
            )
        }
        private set

    fun handle(event: LogsAuditScreenEvent) {
        when (event) {
            LogsAuditScreenEvent.Refresh -> {
                val selectedRowId = state.selectedRow?.id
                state = buildState(filter = state.filter, selectedRowId = selectedRowId)
                if (selectedRowId != null && state.selectedRow == null) {
                    state = buildState(filter = state.filter)
                }
            }
            LogsAuditScreenEvent.CopyFilteredSummary -> {
                state = buildState(filter = state.filter, selectedRowId = state.selectedRow?.id)
                if (LogsAuditScreenAction.CopyFilteredSummary in state.availableActions) {
                    recordAuditAction("copy_filtered_summary", "rowCount=${state.rows.size}")
                        ?.let(pendingEffects::add)
                    pendingEffects.add(LogsAuditScreenEffect.CopyText(state.copyableFilteredSummary))
                }
            }
            LogsAuditScreenEvent.CopySelectedRecord -> {
                state = buildState(filter = state.filter, selectedRowId = state.selectedRow?.id)
                val selectedRow = state.selectedRow
                if (selectedRow != null) {
                    recordAuditAction(
                        titleAction = "copy_selected_record",
                        detail =
                            "rowCount=${state.rows.size} " +
                                "selectedCategory=${selectedRow.category.name}",
                    )?.let(pendingEffects::add)
                }
                state.copyableSelectedRecord?.let { copyText ->
                    pendingEffects.add(LogsAuditScreenEffect.CopyText(copyText))
                }
            }
            LogsAuditScreenEvent.ExportRedactedBundle -> {
                state = buildState(filter = state.filter, selectedRowId = state.selectedRow?.id)
                state.exportBundle?.let { bundle ->
                    recordAuditAction(
                        titleAction = "export_redacted_bundle",
                        detail = "rowCount=${bundle.rowCount} fileName=${bundle.fileName}",
                    )?.let(pendingEffects::add)
                    pendingEffects.add(LogsAuditScreenEffect.ExportBundle(bundle))
                }
            }
            is LogsAuditScreenEvent.SelectRecord -> {
                state = buildState(filter = state.filter, selectedRowId = event.rowId)
            }
            LogsAuditScreenEvent.ClearSelection -> {
                state = buildState(filter = state.filter)
            }
            is LogsAuditScreenEvent.UpdateFilter -> {
                val selectedRowId = state.selectedRow?.id
                state = buildState(filter = event.filter, selectedRowId = selectedRowId)
                if (selectedRowId != null && state.selectedRow == null) {
                    state = buildState(filter = event.filter)
                }
            }
        }
    }

    fun consumeEffects(): List<LogsAuditScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun buildState(
        filter: LogsAuditScreenFilter = LogsAuditScreenFilter(),
        selectedRowId: String? = null,
    ): LogsAuditScreenState = LogsAuditScreenState.from(
        rows = rowsProvider(),
        selectedRowId = selectedRowId,
        filter = filter,
        secrets = secretsProvider(),
        exportSupported = exportSupported,
        exportGeneratedAtEpochMillis = exportGeneratedAtEpochMillisProvider(),
        maxRows = maxRows,
    )

    private fun recordAuditAction(
        titleAction: String,
        detail: String,
    ): LogsAuditScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        LogsAuditScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.Audit,
                severity = LogsAuditRecordSeverity.Info,
                title = "Logs/Audit $titleAction",
                detail = "action=$titleAction $detail",
            ),
        )
    } else {
        null
    }
}

internal sealed interface LogsAuditScreenEvent {
    data object Refresh : LogsAuditScreenEvent

    data class SelectRecord(
        val rowId: String,
    ) : LogsAuditScreenEvent

    data class UpdateFilter(
        val filter: LogsAuditScreenFilter,
    ) : LogsAuditScreenEvent

    data object ClearSelection : LogsAuditScreenEvent

    data object CopySelectedRecord : LogsAuditScreenEvent

    data object CopyFilteredSummary : LogsAuditScreenEvent

    data object ExportRedactedBundle : LogsAuditScreenEvent
}

internal sealed interface LogsAuditScreenEffect {
    data class CopyText(
        val text: String,
    ) : LogsAuditScreenEffect

    data class ExportBundle(
        val bundle: LogsAuditScreenExportBundle,
    ) : LogsAuditScreenEffect

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
    ) : LogsAuditScreenEffect
}

internal fun logsAuditScreenRowsFromPersistedAuditRecords(
    managementRecords: List<PersistedManagementApiAuditRecord>,
    rootRecords: List<PersistedRootCommandAuditRecord>,
    foregroundServiceRecords: List<PersistedForegroundServiceAuditRecord> = emptyList(),
    genericRecords: List<PersistedLogsAuditRecord> = emptyList(),
): List<LogsAuditScreenInputRow> = managementRecords
    .mapIndexed { index, record -> record.toLogsAuditScreenInputRow(index) } +
    rootRecords.mapIndexed { index, record -> record.toLogsAuditScreenInputRow(index) } +
    foregroundServiceRecords.mapIndexed { index, record -> record.toLogsAuditScreenInputRow(index) } +
    genericRecords.mapIndexed { index, record -> record.toLogsAuditScreenInputRow(index) }

@Composable
private fun LogsAuditSelectedRecord(
    selectedRow: LogsAuditScreenRow?,
    actionsEnabled: Boolean,
    onClearSelection: () -> Unit,
) {
    if (selectedRow == null) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Selected record",
            style = MaterialTheme.typography.titleMedium,
        )
        LogsAuditField("Selected", "${selectedRow.category.label} | ${selectedRow.title}")
        OutlinedButton(
            onClick = onClearSelection,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear selection")
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsAuditCategoryFilter(
    state: LogsAuditScreenState,
    onUpdateFilter: (LogsAuditScreenFilter) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Category",
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter.category == null,
                onClick = { onUpdateFilter(state.filter.copy(category = null)) },
                label = { Text("All categories") },
            )
            LogsAuditScreenCategory.entries.forEach { category ->
                FilterChip(
                    selected = state.filter.category == category,
                    onClick = { onUpdateFilter(state.filter.copy(category = category)) },
                    label = { Text(category.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsAuditSeverityFilter(
    state: LogsAuditScreenState,
    onUpdateFilter: (LogsAuditScreenFilter) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Severity",
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter.severity == null,
                onClick = { onUpdateFilter(state.filter.copy(severity = null)) },
                label = { Text("All severities") },
            )
            LogsAuditScreenSeverity.entries.forEach { severity ->
                FilterChip(
                    selected = state.filter.severity == severity,
                    onClick = { onUpdateFilter(state.filter.copy(severity = severity)) },
                    label = { Text(severity.label) },
                )
            }
        }
    }
}

@Composable
private fun LogsAuditTimeWindowFilter(
    state: LogsAuditScreenState,
    onUpdateFilter: (LogsAuditScreenFilter) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Time window",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value =
                    state.filter.fromEpochMillis
                        ?.toString()
                        .orEmpty(),
                onValueChange = { from ->
                    val fromEpochMillis = parseEpochMillisFilterInput(from)
                    val hasValidFromInput = from.isBlank() || fromEpochMillis != null
                    val hasValidFromWindow =
                        fromEpochMillis == null ||
                            state.filter.toEpochMillis == null ||
                            fromEpochMillis <= state.filter.toEpochMillis
                    if (hasValidFromInput && hasValidFromWindow) {
                        onUpdateFilter(state.filter.copy(fromEpochMillis = fromEpochMillis))
                    }
                },
                label = { Text("From timestamp") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value =
                    state.filter.toEpochMillis
                        ?.toString()
                        .orEmpty(),
                onValueChange = { to ->
                    val toEpochMillis = parseEpochMillisFilterInput(to)
                    val hasValidToInput = to.isBlank() || toEpochMillis != null
                    val hasValidToWindow =
                        toEpochMillis == null ||
                            state.filter.fromEpochMillis == null ||
                            state.filter.fromEpochMillis <= toEpochMillis
                    if (hasValidToInput && hasValidToWindow) {
                        onUpdateFilter(state.filter.copy(toEpochMillis = toEpochMillis))
                    }
                },
                label = { Text("To timestamp") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = { onUpdateFilter(state.filter.copy(fromEpochMillis = null, toEpochMillis = null)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("All time")
        }
    }
}

@Composable
private fun LogsAuditSearchFilter(
    state: LogsAuditScreenState,
    onUpdateFilter: (LogsAuditScreenFilter) -> Unit,
) {
    OutlinedTextField(
        value = state.searchDisplayText,
        onValueChange = { search -> onUpdateFilter(state.filter.copy(search = search)) },
        label = { Text("Search logs") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LogsAuditActionRow(
    state: LogsAuditScreenState,
    actionsEnabled: Boolean,
    onCopySelectedRecord: (String) -> Unit,
    onCopyFilteredSummary: (String) -> Unit,
    onExportRedactedBundle: (LogsAuditScreenExportBundle) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { state.copyableSelectedRecord?.let(onCopySelectedRecord) },
            enabled = actionsEnabled && LogsAuditScreenAction.CopySelectedRecord in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy selected record")
        }
        OutlinedButton(
            onClick = { onCopyFilteredSummary(state.copyableFilteredSummary) },
            enabled = actionsEnabled && LogsAuditScreenAction.CopyFilteredSummary in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy filtered summary")
        }
        OutlinedButton(
            onClick = { state.exportBundle?.let(onExportRedactedBundle) },
            enabled = actionsEnabled && LogsAuditScreenAction.ExportRedactedBundle in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export redacted bundle")
        }
    }
}

@Composable
private fun LogsAuditRow(
    row: LogsAuditScreenRow,
    actionsEnabled: Boolean,
    onSelectRecord: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = actionsEnabled,
                    onClick = { onSelectRecord(row.id) },
                ),
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

private fun PersistedManagementApiAuditRecord.toLogsAuditScreenInputRow(index: Int): LogsAuditScreenInputRow {
    val operationLabel = operation?.name ?: "unknown"
    return LogsAuditScreenInputRow(
        id = "management-api-$index-$occurredAtEpochMillis-$operationLabel-${outcome.name}",
        category = LogsAuditScreenCategory.ManagementApi,
        severity = managementAuditSeverity(),
        occurredAtEpochMillis = occurredAtEpochMillis,
        title = "Management API $operationLabel ${outcome.titleSuffix()}",
        detail = "status=${statusCode?.toString() ?: "none"} disposition=${disposition?.name ?: "none"}",
    )
}

private fun PersistedManagementApiAuditRecord.managementAuditSeverity(): LogsAuditScreenSeverity = when (outcome) {
    ManagementApiAuditOutcome.Responded ->
        when (statusCode) {
            in 100..399 -> LogsAuditScreenSeverity.Info
            in 400..499 -> LogsAuditScreenSeverity.Warning
            else -> LogsAuditScreenSeverity.Failed
        }
    ManagementApiAuditOutcome.RouteRejected,
    ManagementApiAuditOutcome.AuthorizationRejected,
    -> LogsAuditScreenSeverity.Warning
    ManagementApiAuditOutcome.HandlerFailed -> LogsAuditScreenSeverity.Failed
}

private fun ManagementApiAuditOutcome.titleSuffix(): String = when (this) {
    ManagementApiAuditOutcome.Responded -> "responded"
    ManagementApiAuditOutcome.RouteRejected -> "route rejected"
    ManagementApiAuditOutcome.HandlerFailed -> "handler failed"
    ManagementApiAuditOutcome.AuthorizationRejected -> "authorization rejected"
}

private fun PersistedRootCommandAuditRecord.toLogsAuditScreenInputRow(index: Int): LogsAuditScreenInputRow = LogsAuditScreenInputRow(
    id = "root-command-$index-$occurredAtEpochMillis-${category.name}-${phase.name}",
    category = LogsAuditScreenCategory.RootCommands,
    severity = rootAuditSeverity(),
    occurredAtEpochMillis = occurredAtEpochMillis,
    title = "Root command ${category.name} ${phase.titleSuffix()}",
    detail =
        "outcome=${outcome?.name ?: "none"} " +
            "exitCode=${exitCode?.toString() ?: "none"} " +
            "stdout=${stdout ?: "none"} " +
            "stderr=${stderr ?: "none"}",
)

private fun PersistedRootCommandAuditRecord.rootAuditSeverity(): LogsAuditScreenSeverity = when {
    phase == RootCommandAuditPhase.Started -> LogsAuditScreenSeverity.Info
    outcome == RootCommandOutcome.Success -> LogsAuditScreenSeverity.Info
    else -> LogsAuditScreenSeverity.Failed
}

private fun RootCommandAuditPhase.titleSuffix(): String = when (this) {
    RootCommandAuditPhase.Started -> "started"
    RootCommandAuditPhase.Completed -> "completed"
}

private fun PersistedForegroundServiceAuditRecord.toLogsAuditScreenInputRow(index: Int): LogsAuditScreenInputRow = LogsAuditScreenInputRow(
    id = "foreground-service-$index-$occurredAtEpochMillis-${event.name}-${outcome.name}",
    category = LogsAuditScreenCategory.AppRuntime,
    severity = foregroundServiceAuditSeverity(),
    occurredAtEpochMillis = occurredAtEpochMillis,
    title = "Foreground service ${event.name} ${outcome.name}",
    detail = "command=${command.name} source=${source.name}",
)

private fun PersistedForegroundServiceAuditRecord.foregroundServiceAuditSeverity(): LogsAuditScreenSeverity = when (outcome) {
    ForegroundServiceAuditOutcome.RuntimeFailed -> LogsAuditScreenSeverity.Failed
    ForegroundServiceAuditOutcome.RuntimeStarted,
    ForegroundServiceAuditOutcome.RuntimeStopped,
    -> LogsAuditScreenSeverity.Info
}

private fun PersistedLogsAuditRecord.toLogsAuditScreenInputRow(index: Int): LogsAuditScreenInputRow = LogsAuditScreenInputRow(
    id = "generic-log-$index-$occurredAtEpochMillis-${category.name}-${severity.name}",
    category = category.toScreenCategory(),
    severity = severity.toScreenSeverity(),
    occurredAtEpochMillis = occurredAtEpochMillis,
    title = title,
    detail = detail,
)

private fun LogsAuditRecordCategory.toScreenCategory(): LogsAuditScreenCategory = when (this) {
    LogsAuditRecordCategory.AppRuntime -> LogsAuditScreenCategory.AppRuntime
    LogsAuditRecordCategory.ProxyServer -> LogsAuditScreenCategory.ProxyServer
    LogsAuditRecordCategory.CloudflareTunnel -> LogsAuditScreenCategory.CloudflareTunnel
    LogsAuditRecordCategory.Rotation -> LogsAuditScreenCategory.Rotation
    LogsAuditRecordCategory.Audit -> LogsAuditScreenCategory.Audit
}

private fun LogsAuditRecordSeverity.toScreenSeverity(): LogsAuditScreenSeverity = when (this) {
    LogsAuditRecordSeverity.Info -> LogsAuditScreenSeverity.Info
    LogsAuditRecordSeverity.Warning -> LogsAuditScreenSeverity.Warning
    LogsAuditRecordSeverity.Failed -> LogsAuditScreenSeverity.Failed
}

private fun LogsAuditScreenFilter.timeWindowText(): String = when {
    fromEpochMillis == null && toEpochMillis == null -> "All"
    fromEpochMillis != null && toEpochMillis != null -> "$fromEpochMillis to $toEpochMillis"
    fromEpochMillis != null -> "From $fromEpochMillis"
    else -> "Until $toEpochMillis"
}

private fun parseEpochMillisFilterInput(input: String): Long? = if (input.isBlank()) {
    null
} else {
    input.trim().toLongOrNull()?.takeIf { epochMillis -> epochMillis >= 0 }
}

private fun String.normalizedLogsAuditSearchText(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
