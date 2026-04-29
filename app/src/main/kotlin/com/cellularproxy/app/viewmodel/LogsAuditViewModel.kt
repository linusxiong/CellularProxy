package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.ui.LogsAuditScreenController
import com.cellularproxy.app.ui.LogsAuditScreenEffect
import com.cellularproxy.app.ui.LogsAuditScreenEvent
import com.cellularproxy.app.ui.LogsAuditScreenInputRow
import com.cellularproxy.app.ui.LogsAuditScreenState
import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class LogsAuditViewModel(
    rowsProvider: () -> List<LogsAuditScreenInputRow> = { emptyList() },
    secretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    exportSupported: Boolean = false,
    exportGeneratedAtEpochMillisProvider: () -> Long = { 0L },
    auditOccurredAtEpochMillisProvider: () -> Long = { exportGeneratedAtEpochMillisProvider() },
    auditActionsEnabled: Boolean = false,
    maxRows: Int? = null,
    loadInitialState: Boolean = true,
) : ViewModel() {
    private val controller =
        LogsAuditScreenController(
            rowsProvider = rowsProvider,
            secretsProvider = secretsProvider,
            exportSupported = exportSupported,
            exportGeneratedAtEpochMillisProvider = exportGeneratedAtEpochMillisProvider,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
            auditActionsEnabled = auditActionsEnabled,
            maxRows = maxRows,
            loadInitialState = loadInitialState,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<LogsAuditScreenState> = mutableState.asStateFlow()

    fun handle(event: LogsAuditScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<LogsAuditScreenEffect> = controller.consumeEffects()
}
