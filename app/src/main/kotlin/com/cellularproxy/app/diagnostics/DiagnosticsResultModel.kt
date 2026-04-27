package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.logging.LogRedactor
import kotlin.time.Duration

data class DiagnosticsResultModel(
    val results: List<DiagnosticResultItem>,
    val copyableSummary: String,
) {
    companion object {
        fun empty(): DiagnosticsResultModel = from(completed = emptyList())

        fun running(type: DiagnosticCheckType): DiagnosticsResultModel = from(
            completed = emptyList(),
            running = setOf(type),
        )

        fun from(
            completed: List<DiagnosticRunRecord>,
            running: Set<DiagnosticCheckType> = emptySet(),
            secrets: LogRedactionSecrets = LogRedactionSecrets(),
        ): DiagnosticsResultModel {
            val completedByType = completed.associateBy(DiagnosticRunRecord::type)
            val results =
                DiagnosticCheckType.entries.map { type ->
                    completedByType[type]
                        ?.toResultItem(secrets)
                        ?: DiagnosticResultItem(
                            type = type,
                            label = type.label,
                            status =
                                if (type in running) {
                                    DiagnosticResultStatus.Running
                                } else {
                                    DiagnosticResultStatus.NotRun
                                },
                        )
                }
            return DiagnosticsResultModel(
                results = results,
                copyableSummary = results.joinToString(separator = "\n", transform = DiagnosticResultItem::summaryLine),
            )
        }
    }
}

data class DiagnosticRunRecord(
    val type: DiagnosticCheckType,
    val status: DiagnosticResultStatus,
    val duration: Duration,
    val errorCategory: String? = null,
    val details: String? = null,
)

data class DiagnosticResultItem(
    val type: DiagnosticCheckType,
    val label: String,
    val status: DiagnosticResultStatus,
    val durationMillis: Long? = null,
    val errorCategory: String? = null,
    val details: String? = null,
) {
    fun summaryLine(): String {
        val suffixes =
            listOfNotNull(
                durationMillis?.let { "in ${it}ms" },
                errorCategory?.let { "($it)" },
            )
        val statusAndMetadata =
            (listOf(status.label) + suffixes)
                .joinToString(separator = " ")
        val detailSuffix = details?.let { " - $it" }.orEmpty()
        return "$label: $statusAndMetadata$detailSuffix"
    }
}

enum class DiagnosticCheckType(
    val label: String,
) {
    RootAvailability("Root availability"),
    SelectedRoute("Selected route"),
    PublicIp("Public IP"),
    ProxyBind("Proxy bind"),
    LocalManagementApi("Local management API"),
    CloudflareTunnel("Cloudflare tunnel"),
    CloudflareManagementApi("Cloudflare management API"),
}

enum class DiagnosticResultStatus(
    val label: String,
) {
    NotRun("not run"),
    Running("running"),
    Passed("passed"),
    Warning("warning"),
    Failed("failed"),
}

private fun DiagnosticRunRecord.toResultItem(secrets: LogRedactionSecrets): DiagnosticResultItem = DiagnosticResultItem(
    type = type,
    label = type.label,
    status = status,
    durationMillis = duration.inWholeMilliseconds,
    errorCategory =
        errorCategory
            ?.takeIf { status == DiagnosticResultStatus.Failed }
            ?.let { LogRedactor.redact(it, secrets) },
    details = details?.let { LogRedactor.redact(it, secrets) },
)
