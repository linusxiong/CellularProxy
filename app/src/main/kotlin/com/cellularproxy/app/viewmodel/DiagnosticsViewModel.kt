package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.app.ui.DiagnosticsScreenController
import com.cellularproxy.app.ui.DiagnosticsScreenEffect
import com.cellularproxy.app.ui.DiagnosticsScreenEvent
import com.cellularproxy.app.ui.DiagnosticsScreenState
import com.cellularproxy.app.ui.runningTypes
import com.cellularproxy.app.ui.withRunningChecks
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
    private val optimisticRunningTypes = mutableSetOf<DiagnosticCheckType>()
    private val controller =
        DiagnosticsScreenController(
            suiteController = suiteController,
            secretsProvider = secretsProvider,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<DiagnosticsScreenState> = mutableState.asStateFlow()

    @Synchronized
    fun handle(event: DiagnosticsScreenEvent) {
        controller.handle(event)
        optimisticRunningTypes.removeAll(event.runningTypes())
        mutableState.value = controller.state.withRunningChecks(optimisticRunningTypes)
    }

    @Synchronized
    fun markRunning(types: Set<DiagnosticCheckType>) {
        optimisticRunningTypes.addAll(types)
        mutableState.value = mutableState.value.withRunningChecks(optimisticRunningTypes)
    }

    @Synchronized
    fun consumeEffects(): List<DiagnosticsScreenEffect> = controller.consumeEffects()
}
