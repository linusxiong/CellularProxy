package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.app.ui.DiagnosticsScreenController
import com.cellularproxy.app.ui.DiagnosticsScreenEffect
import com.cellularproxy.app.ui.DiagnosticsScreenEvent
import com.cellularproxy.app.ui.DiagnosticsScreenState
import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DiagnosticsViewModel(
    suiteController: DiagnosticsSuiteController,
    secrets: LogRedactionSecrets = LogRedactionSecrets(),
    secretsProvider: () -> LogRedactionSecrets = { secrets },
    auditActionsEnabled: Boolean = false,
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val controller =
        DiagnosticsScreenController(
            suiteController = suiteController,
            secretsProvider = secretsProvider,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<DiagnosticsScreenState> = mutableState.asStateFlow()

    fun handle(event: DiagnosticsScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<DiagnosticsScreenEffect> = controller.consumeEffects()
}
