package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.DiagnosticCheck
import com.cellularproxy.app.diagnostics.DiagnosticCheckResult
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticsResultModel
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.diagnostics.PublicIpDiagnosticsProbeResult
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsScreenControllerTest {
    @Test
    fun `controller records audit effects for executed diagnostics actions`() {
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.LocalManagementApi to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Passed,
                                            details = "local ok",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
                secrets = LogRedactionSecrets(),
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 42L },
            )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        controller.handle(DiagnosticsScreenEvent.CopyCheck(DiagnosticCheckType.LocalManagementApi))

        val auditRecords =
            controller
                .consumeEffects()
                .filterIsInstance<DiagnosticsScreenEffect.RecordAuditAction>()
                .map(DiagnosticsScreenEffect.RecordAuditAction::record)

        assertEquals(
            listOf(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 42L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Diagnostics run_check",
                    detail = "action=run_check check=local_management_api",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 42L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Diagnostics copy_check",
                    detail = "action=copy_check check=local_management_api",
                ),
            ),
            auditRecords,
        )
    }

    @Test
    fun `controller runs selected and bulk-safe diagnostics into screen state`() {
        val runCounts = DiagnosticCheckType.entries.associateWith { 0 }.toMutableMap()
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            DiagnosticCheckType.entries.associateWith { type ->
                                DiagnosticCheck {
                                    runCounts[type] = runCounts.getValue(type) + 1
                                    DiagnosticCheckResult(
                                        status = DiagnosticResultStatus.Passed,
                                        details = "${type.label} ok",
                                    )
                                }
                            },
                        nanoTime = { 0L },
                    ),
                secrets = LogRedactionSecrets(),
            )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.RootAvailability))

        assertEquals("1 of 7 checks complete", controller.state.completionSummary)
        assertEquals(
            DiagnosticResultStatus.Passed.label,
            controller
                .state
                .items
                .single { it.type == DiagnosticCheckType.RootAvailability }
                .status,
        )

        controller.handle(DiagnosticsScreenEvent.RunAllChecks)

        assertEquals("5 of 7 checks complete", controller.state.completionSummary)
        assertEquals(DiagnosticResultStatus.Passed.label, controller.state.overallStatus)
        assertEquals(0, runCounts.getValue(DiagnosticCheckType.CloudflareTunnel))
        assertEquals(0, runCounts.getValue(DiagnosticCheckType.CloudflareManagementApi))
        assertEquals(
            DiagnosticResultStatus.NotRun.label,
            controller
                .state
                .items
                .single { it.type == DiagnosticCheckType.CloudflareTunnel }
                .status,
        )
        assertEquals(
            DiagnosticResultStatus.NotRun.label,
            controller
                .state
                .items
                .single { it.type == DiagnosticCheckType.CloudflareManagementApi }
                .status,
        )
        assertTrue(
            controller
                .state
                .items
                .filterNot { item -> item.type in explicitCloudflareDiagnosticCheckTypes }
                .all { item -> item.status == DiagnosticResultStatus.Passed.label },
        )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.CloudflareTunnel))
        assertEquals(1, runCounts.getValue(DiagnosticCheckType.CloudflareTunnel))

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.CloudflareManagementApi))

        assertEquals(1, runCounts.getValue(DiagnosticCheckType.CloudflareManagementApi))
        assertEquals("7 of 7 checks complete", controller.state.completionSummary)
    }

    @Test
    fun `screen state labels running diagnostic duration as in progress`() {
        val state =
            DiagnosticsScreenState.from(
                DiagnosticsResultModel.running(DiagnosticCheckType.PublicIp),
            )

        val item = state.items.single { it.type == DiagnosticCheckType.PublicIp }
        assertEquals(DiagnosticResultStatus.Running.label, item.status)
        assertEquals("In progress", item.duration)
        assertTrue(item.summaryLine().contains("Public IP: running"))
        assertFalse(item.summaryLine().contains("Not run"))
    }

    @Test
    fun `controller copy summary emits redacted state derived payload once`() {
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.LocalManagementApi to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Failed,
                                            errorCategory = "authorization=management-secret",
                                            details = "Authorization: Bearer management-secret",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
                secrets = LogRedactionSecrets(managementApiToken = "management-secret"),
            )

        controller.handle(DiagnosticsScreenEvent.CopySummary)
        assertTrue(controller.consumeEffects().isEmpty())

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        controller.handle(DiagnosticsScreenEvent.CopySummary)

        val copyText = (controller.consumeEffects().single() as DiagnosticsScreenEffect.CopyText).text
        assertTrue(copyText.contains("Local management API: failed"))
        assertFalse(copyText.contains("management-secret"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller copy summary uses latest redaction secrets provider`() {
        var secrets = LogRedactionSecrets()
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.LocalManagementApi to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Failed,
                                            details = "Proxy credential rotated-secret",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
                secretsProvider = { secrets },
            )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        secrets = LogRedactionSecrets(proxyCredential = "rotated-secret")
        controller.handle(DiagnosticsScreenEvent.CopySummary)

        val copyText = (controller.consumeEffects().single() as DiagnosticsScreenEffect.CopyText).text
        assertFalse(copyText.contains("rotated-secret"))
        assertTrue(copyText.contains("Proxy credential [REDACTED]"))
    }

    @Test
    fun `controller refresh rebuilds visible diagnostics with latest redaction secrets without effects`() {
        var secrets = LogRedactionSecrets()
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.LocalManagementApi to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Failed,
                                            details = "Proxy credential rotated-management-token",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
                secretsProvider = { secrets },
            )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        val unredactedDetails =
            controller
                .state
                .items
                .single { item -> item.type == DiagnosticCheckType.LocalManagementApi }
                .details
        assertTrue(unredactedDetails.contains("rotated-management-token"))

        secrets = LogRedactionSecrets(proxyCredential = "rotated-management-token")
        controller.handle(DiagnosticsScreenEvent.Refresh)

        val redactedDetails =
            controller
                .state
                .items
                .single { item -> item.type == DiagnosticCheckType.LocalManagementApi }
                .details
        assertFalse(redactedDetails.contains("rotated-management-token"))
        assertTrue(redactedDetails.contains("[REDACTED]"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller copies selected completed diagnostic result only`() {
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.LocalManagementApi to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Failed,
                                            errorCategory = "authorization=management-secret",
                                            details = "Authorization: Bearer management-secret",
                                        )
                                    },
                                DiagnosticCheckType.PublicIp to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = DiagnosticResultStatus.Passed,
                                            details = "public ip ok",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
                secrets = LogRedactionSecrets(managementApiToken = "management-secret"),
            )

        controller.handle(DiagnosticsScreenEvent.CopyCheck(DiagnosticCheckType.LocalManagementApi))
        assertTrue(controller.consumeEffects().isEmpty())

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.PublicIp))
        controller.handle(DiagnosticsScreenEvent.CopyCheck(DiagnosticCheckType.LocalManagementApi))

        val copyText = (controller.consumeEffects().single() as DiagnosticsScreenEffect.CopyText).text
        assertTrue(copyText.contains("Local management API: failed"))
        assertFalse(copyText.contains("Public IP"))
        assertFalse(copyText.contains("management-secret"))
    }

    @Test
    fun `controller empty suite reports explicit missing check fallback`() {
        val controller =
            DiagnosticsScreenController(
                suiteController = DiagnosticsSuiteController(checks = emptyMap(), nanoTime = { 0L }),
                secrets = LogRedactionSecrets(),
            )

        controller.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.RootAvailability))

        val rootItem = controller.state.items.single { item -> item.type == DiagnosticCheckType.RootAvailability }
        assertEquals(DiagnosticResultStatus.Failed.label, rootItem.status)
        assertEquals("missing-check", rootItem.errorCategory)
        assertTrue(rootItem.details.contains("No diagnostic check registered"))
    }

    @Test
    fun `local management diagnostics probe maps authenticated unauthorized unavailable and error responses`() {
        assertEquals(
            LocalManagementApiProbeResult.Authenticated,
            localManagementApiProbeResultFrom { LocalManagementApiActionResponse(statusCode = 200) },
        )
        assertEquals(
            LocalManagementApiProbeResult.Unauthorized,
            localManagementApiProbeResultFrom { LocalManagementApiActionResponse(statusCode = 401) },
        )
        assertEquals(
            LocalManagementApiProbeResult.Unavailable,
            localManagementApiProbeResultFrom { LocalManagementApiActionResponse(statusCode = 503) },
        )
        assertEquals(
            LocalManagementApiProbeResult.Error,
            localManagementApiProbeResultFrom { LocalManagementApiActionResponse(statusCode = 409) },
        )
        assertEquals(
            LocalManagementApiProbeResult.Unavailable,
            localManagementApiProbeResultFrom { error("connection refused") },
        )
    }

    @Test
    fun `cloudflare management diagnostics probe maps configuration and HTTP outcomes`() {
        assertEquals(
            CloudflareManagementApiProbeResult.NotConfigured,
            cloudflareManagementApiProbeResultFrom(
                config =
                    AppConfig
                        .default()
                        .copy(cloudflare = CloudflareConfig(enabled = false)),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 200) },
            ),
        )
        assertEquals(
            CloudflareManagementApiProbeResult.NotConfigured,
            cloudflareManagementApiProbeResultFrom(
                config =
                    AppConfig
                        .default()
                        .copy(cloudflare = CloudflareConfig(enabled = true, tunnelTokenPresent = true)),
                tunnelTokenPresent = false,
                request = { LocalManagementApiActionResponse(statusCode = 200) },
            ),
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Authenticated,
            cloudflareManagementApiProbeResultFrom(
                config = configuredCloudflare(tunnelTokenPresent = false),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 204) },
            ),
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Unauthorized,
            cloudflareManagementApiProbeResultFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 401) },
            ),
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Unavailable,
            cloudflareManagementApiProbeResultFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 503) },
            ),
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Error,
            cloudflareManagementApiProbeResultFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { error("tls failed") },
            ),
        )
    }

    @Test
    fun `management diagnostics probes safely map invalid sensitive config load results`() {
        assertEquals(
            LocalManagementApiProbeResult.Unavailable,
            localManagementApiProbeResultFromSensitiveConfigLoadResult(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ) { _ ->
                error("invalid sensitive storage must not dispatch local management probe")
            },
        )
        assertEquals(
            LocalManagementApiProbeResult.Unavailable,
            localManagementApiProbeResultFromSensitiveConfigLoadResult(
                SensitiveConfigLoadResult.MissingRequiredSecrets,
            ) { _ ->
                error("missing sensitive storage must not dispatch local management probe")
            },
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Error,
            cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
                config = configuredCloudflare(),
                result = SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ) { _ ->
                error("invalid sensitive storage must not dispatch Cloudflare management probe")
            },
        )
        assertEquals(
            CloudflareManagementApiProbeResult.NotConfigured,
            cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
                config = configuredCloudflare(),
                result = SensitiveConfigLoadResult.MissingRequiredSecrets,
            ) { _ ->
                error("missing sensitive storage must not dispatch Cloudflare management probe")
            },
        )
        assertEquals(
            CloudflareManagementApiProbeResult.Authenticated,
            cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
                config = configuredCloudflare(),
                result = SensitiveConfigLoadResult.Loaded(validSensitiveConfig()),
            ) { _ ->
                LocalManagementApiActionResponse(statusCode = 200)
            },
        )
    }

    @Test
    fun `public ip diagnostics probe maps authenticated ip responses safely`() {
        assertEquals(
            PublicIpDiagnosticsProbeResult.Observed("203.0.113.44"),
            publicIpDiagnosticsProbeResultFrom {
                LocalManagementApiActionResponse(
                    statusCode = 200,
                    body = """{"publicIp":"203.0.113.44"}""",
                )
            },
        )
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFrom {
                LocalManagementApiActionResponse(
                    statusCode = 200,
                    body = """{"publicIp":null}""",
                )
            },
        )
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFrom {
                LocalManagementApiActionResponse(statusCode = 503)
            },
        )
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFrom { error("request failed") },
        )
    }

    @Test
    fun `public ip diagnostics probe rejects non literal response text`() {
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFrom {
                LocalManagementApiActionResponse(
                    statusCode = 200,
                    body = """{"publicIp":"203.0.113.44?token=query-secret"}""",
                )
            },
        )
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFrom {
                LocalManagementApiActionResponse(
                    statusCode = 200,
                    body = """{"publicIp":"management.example.test"}""",
                )
            },
        )
    }

    @Test
    fun `public ip diagnostics probe safely maps invalid sensitive config load results`() {
        assertEquals(
            PublicIpDiagnosticsProbeResult.Unavailable,
            publicIpDiagnosticsProbeResultFromSensitiveConfigLoadResult(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ) { _ ->
                error("invalid sensitive storage must not dispatch public IP probe")
            },
        )
        assertEquals(
            PublicIpDiagnosticsProbeResult.Observed("203.0.113.45"),
            publicIpDiagnosticsProbeResultFromSensitiveConfigLoadResult(
                SensitiveConfigLoadResult.Loaded(validSensitiveConfig()),
            ) { _ ->
                LocalManagementApiActionResponse(
                    statusCode = 200,
                    body = """{"publicIp":"203.0.113.45"}""",
                )
            },
        )
    }
}

private val explicitCloudflareDiagnosticCheckTypes =
    setOf(DiagnosticCheckType.CloudflareTunnel, DiagnosticCheckType.CloudflareManagementApi)

private fun configuredCloudflare(tunnelTokenPresent: Boolean = true): AppConfig = AppConfig.default().copy(
    cloudflare =
        CloudflareConfig(
            enabled = true,
            tunnelTokenPresent = tunnelTokenPresent,
            managementHostnameLabel = "management.example.test",
        ),
)

private fun validSensitiveConfig() = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
    cloudflareTunnelToken =
        "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0=",
)
