package com.cellularproxy.app.ui

import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.shared.logging.LogRedactionSecrets

internal class DiagnosticsScreenController(
    private val suiteController: DiagnosticsSuiteController,
    secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
) {
    private val pendingEffects = mutableListOf<DiagnosticsScreenEffect>()
    var state: DiagnosticsScreenState = buildState()
        private set

    fun handle(event: DiagnosticsScreenEvent) {
        when (event) {
            DiagnosticsScreenEvent.CopySummary -> {
                state = buildState()
                if (DiagnosticsScreenAction.CopySummary in state.availableActions) {
                    pendingEffects.add(DiagnosticsScreenEffect.CopyText(state.copyableSummary))
                }
            }
            DiagnosticsScreenEvent.RunAllChecks -> {
                if (DiagnosticsScreenAction.RunAllChecks in state.availableActions) {
                    bulkSafeDiagnosticCheckTypes.forEach(suiteController::run)
                    state = buildState()
                }
            }
            is DiagnosticsScreenEvent.RunCheck -> {
                val item = state.items.singleOrNull { screenItem -> screenItem.type == event.type }
                if (item != null && DiagnosticsScreenAction.RunCheck in item.availableActions) {
                    suiteController.run(event.type)
                    state = buildState()
                }
            }
            is DiagnosticsScreenEvent.CopyCheck -> {
                state = buildState()
                val item = state.items.singleOrNull { screenItem -> screenItem.type == event.type }
                if (item != null && DiagnosticsScreenAction.CopyCheck in item.availableActions) {
                    pendingEffects.add(DiagnosticsScreenEffect.CopyText(item.summaryLine()))
                }
            }
            DiagnosticsScreenEvent.Refresh -> state = buildState()
        }
    }

    fun consumeEffects(): List<DiagnosticsScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun buildState(): DiagnosticsScreenState = DiagnosticsScreenState.from(
        model = suiteController.resultModel(secretsProvider()),
    )
}

internal sealed interface DiagnosticsScreenEvent {
    data class RunCheck(
        val type: DiagnosticCheckType,
    ) : DiagnosticsScreenEvent

    data class CopyCheck(
        val type: DiagnosticCheckType,
    ) : DiagnosticsScreenEvent

    data object RunAllChecks : DiagnosticsScreenEvent

    data object CopySummary : DiagnosticsScreenEvent

    data object Refresh : DiagnosticsScreenEvent
}

internal sealed interface DiagnosticsScreenEffect {
    data class CopyText(
        val text: String,
    ) : DiagnosticsScreenEffect
}
