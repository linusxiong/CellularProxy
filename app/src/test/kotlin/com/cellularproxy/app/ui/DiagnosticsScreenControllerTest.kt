package com.cellularproxy.app.ui

import com.cellularproxy.app.diagnostics.DiagnosticCheck
import com.cellularproxy.app.diagnostics.DiagnosticCheckResult
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
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
}
