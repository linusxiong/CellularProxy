package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.shared.logging.LogRedactionSecrets

internal class DiagnosticsScreenController(
    private val suiteController: DiagnosticsSuiteController,
    secrets: LogRedactionSecrets = LogRedactionSecrets(),
    private val secretsProvider: () -> LogRedactionSecrets = { secrets },
    private val auditActionsEnabled: Boolean = false,
    private val auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    private val pendingEffects = mutableListOf<DiagnosticsScreenEffect>()
    var state: DiagnosticsScreenState = buildState()
        private set

    fun handle(event: DiagnosticsScreenEvent) {
        when (event) {
            DiagnosticsScreenEvent.CopySummary -> {
                state = buildState()
                if (DiagnosticsScreenAction.CopySummary in state.availableActions) {
                    recordAuditAction("copy_summary", null)?.let(pendingEffects::add)
                    pendingEffects.add(DiagnosticsScreenEffect.CopyText(state.copyableSummary))
                }
            }
            DiagnosticsScreenEvent.RunAllChecks -> {
                if (DiagnosticsScreenAction.RunAllChecks in state.availableActions) {
                    bulkSafeDiagnosticCheckTypes.forEach(suiteController::run)
                    state = buildState()
                    recordAuditAction("run_all", null)?.let(pendingEffects::add)
                }
            }
            is DiagnosticsScreenEvent.RunCheck -> {
                val item = state.items.singleOrNull { screenItem -> screenItem.type == event.type }
                if (item != null && DiagnosticsScreenAction.RunCheck in item.availableActions) {
                    suiteController.run(event.type)
                    state = buildState()
                    recordAuditAction("run_check", event.type)?.let(pendingEffects::add)
                }
            }
            is DiagnosticsScreenEvent.CopyCheck -> {
                state = buildState()
                val item = state.items.singleOrNull { screenItem -> screenItem.type == event.type }
                if (item != null && DiagnosticsScreenAction.CopyCheck in item.availableActions) {
                    recordAuditAction("copy_check", event.type)?.let(pendingEffects::add)
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

    private fun recordAuditAction(
        action: String,
        type: DiagnosticCheckType?,
    ): DiagnosticsScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        DiagnosticsScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.Audit,
                severity = LogsAuditRecordSeverity.Info,
                title = "Diagnostics $action",
                detail =
                    listOfNotNull(
                        "action=$action",
                        type?.let { diagnosticType -> "check=${diagnosticType.auditName}" },
                    ).joinToString(separator = " "),
            ),
        )
    } else {
        null
    }
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

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
    ) : DiagnosticsScreenEffect
}

private val DiagnosticCheckType.auditName: String
    get() =
        when (this) {
            DiagnosticCheckType.RootAvailability -> "root_availability"
            DiagnosticCheckType.SelectedRoute -> "selected_route"
            DiagnosticCheckType.PublicIp -> "public_ip"
            DiagnosticCheckType.ProxyBind -> "proxy_bind"
            DiagnosticCheckType.LocalManagementApi -> "local_management_api"
            DiagnosticCheckType.CloudflareTunnel -> "cloudflare_tunnel"
            DiagnosticCheckType.CloudflareManagementApi -> "cloudflare_management_api"
        }
