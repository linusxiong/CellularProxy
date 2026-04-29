package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class DiagnosticsResultModelTest {
    @Test
    fun `default suite starts every diagnostic as not run`() {
        val model = DiagnosticsResultModel.empty()

        assertEquals(DiagnosticCheckType.entries.size, model.results.size)
        assertEquals(DiagnosticResultStatus.NotRun, model.results.first().status)
        assertEquals("Root availability", model.results.first().label)
        assertEquals(null, model.results.first().durationMillis)
        assertEquals(null, model.results.first().errorCategory)
        assertEquals(null, model.results.first().details)
    }

    @Test
    fun `failed diagnostic result carries status duration category and redacted details`() {
        val rawResult =
            DiagnosticRunRecord(
                type = DiagnosticCheckType.CloudflareManagementApi,
                status = DiagnosticResultStatus.Failed,
                duration = 427.milliseconds,
                errorCategory = "Authorization: Bearer management-secret",
                details =
                    "GET https://manage.example.test/api/status?token=management-secret " +
                        "Authorization: Bearer management-secret tunnel=cloudflare-secret",
            )

        val model =
            DiagnosticsResultModel.from(
                completed = listOf(rawResult),
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "management-secret",
                        cloudflareTunnelToken = "cloudflare-secret",
                    ),
            )

        val result = model.results.single { it.type == DiagnosticCheckType.CloudflareManagementApi }
        assertEquals(DiagnosticResultStatus.Failed, result.status)
        assertEquals(427, result.durationMillis)
        assertEquals("Authorization: [REDACTED]", result.errorCategory)
        assertEquals(
            "GET https://manage.example.test/api/status?[REDACTED] Authorization: [REDACTED]",
            result.details,
        )
        assertFalse(result.errorCategory.orEmpty().contains("management-secret"))
        assertFalse(result.details.orEmpty().contains("management-secret"))
        assertFalse(result.details.orEmpty().contains("cloudflare-secret"))
    }

    @Test
    fun `copyable summary is stable ordered and excludes raw secrets`() {
        val model =
            DiagnosticsResultModel.from(
                completed =
                    listOf(
                        DiagnosticRunRecord(
                            type = DiagnosticCheckType.PublicIp,
                            status = DiagnosticResultStatus.Passed,
                            duration = 120.milliseconds,
                            details = "IP 203.0.113.10 via token=management-secret",
                        ),
                        DiagnosticRunRecord(
                            type = DiagnosticCheckType.RootAvailability,
                            status = DiagnosticResultStatus.Warning,
                            duration = 33.milliseconds,
                            errorCategory = "not-authorized",
                            details = "su denied",
                        ),
                    ),
                secrets = LogRedactionSecrets(managementApiToken = "management-secret"),
            )

        assertEquals(
            """
            Root availability: warning in 33ms - su denied
            Selected route: not run
            Public IP: passed in 120ms - IP 203.0.113.10 via token=[REDACTED]
            Proxy bind: not run
            Local management API: not run
            Cloudflare tunnel: not run
            Cloudflare management API: not run
            """.trimIndent(),
            model.copyableSummary,
        )
        assertFalse(model.copyableSummary.contains("management-secret"))
    }

    @Test
    fun `running diagnostic result has no stale duration category or details`() {
        val model = DiagnosticsResultModel.running(DiagnosticCheckType.ProxyBind)

        val result = model.results.single { it.type == DiagnosticCheckType.ProxyBind }
        assertEquals(DiagnosticResultStatus.Running, result.status)
        assertEquals(null, result.durationMillis)
        assertEquals(null, result.errorCategory)
        assertEquals(null, result.details)
    }

    @Test
    fun `warning diagnostic result does not expose raw error category`() {
        val model =
            DiagnosticsResultModel.from(
                completed =
                    listOf(
                        DiagnosticRunRecord(
                            type = DiagnosticCheckType.RootAvailability,
                            status = DiagnosticResultStatus.Warning,
                            duration = 12.milliseconds,
                            errorCategory = "Authorization: Bearer management-secret",
                            details = "root not authorized",
                        ),
                    ),
                secrets = LogRedactionSecrets(managementApiToken = "management-secret"),
            )

        val result = model.results.single { it.type == DiagnosticCheckType.RootAvailability }
        assertEquals(null, result.errorCategory)
        assertFalse(model.copyableSummary.contains("management-secret"))
    }

    @Test
    fun `latest diagnostic record wins when a check is rerun`() {
        val model =
            DiagnosticsResultModel.from(
                completed =
                    listOf(
                        DiagnosticRunRecord(
                            type = DiagnosticCheckType.PublicIp,
                            status = DiagnosticResultStatus.Failed,
                            duration = 100.milliseconds,
                            errorCategory = "timeout",
                            details = "old failure",
                        ),
                        DiagnosticRunRecord(
                            type = DiagnosticCheckType.PublicIp,
                            status = DiagnosticResultStatus.Passed,
                            duration = 80.milliseconds,
                            details = "IP 203.0.113.11",
                        ),
                    ),
            )

        val result = model.results.single { it.type == DiagnosticCheckType.PublicIp }
        assertEquals(DiagnosticResultStatus.Passed, result.status)
        assertEquals(80, result.durationMillis)
        assertEquals(null, result.errorCategory)
        assertEquals("IP 203.0.113.11", result.details)
    }
}
