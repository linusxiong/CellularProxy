package com.cellularproxy.app.ui

import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.DiagnosticCheck
import com.cellularproxy.app.diagnostics.DiagnosticCheckResult
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsScreenControllerTest {
    @Test
    fun `controller runs selected and all diagnostics into screen state`() {
        val controller =
            DiagnosticsScreenController(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            DiagnosticCheckType.entries.associateWith { type ->
                                DiagnosticCheck {
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

        assertEquals("7 of 7 checks complete", controller.state.completionSummary)
        assertTrue(controller.state.items.all { it.status == DiagnosticResultStatus.Passed.label })
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
}

private fun configuredCloudflare(tunnelTokenPresent: Boolean = true): AppConfig = AppConfig.default().copy(
    cloudflare =
        CloudflareConfig(
            enabled = true,
            tunnelTokenPresent = tunnelTokenPresent,
            managementHostnameLabel = "management.example.test",
        ),
)
