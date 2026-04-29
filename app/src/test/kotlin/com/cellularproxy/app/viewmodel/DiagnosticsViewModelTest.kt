package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.diagnostics.DiagnosticCheck
import com.cellularproxy.app.diagnostics.DiagnosticCheckResult
import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticsSuiteController
import com.cellularproxy.app.ui.DiagnosticsScreenEffect
import com.cellularproxy.app.ui.DiagnosticsScreenEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsViewModelTest {
    @Test
    fun `view model exposes diagnostics state as state flow and updates after events`() {
        var selectedRouteStatus = DiagnosticResultStatus.Passed
        val viewModel =
            DiagnosticsViewModel(
                suiteController =
                    DiagnosticsSuiteController(
                        checks =
                            mapOf(
                                DiagnosticCheckType.SelectedRoute to
                                    DiagnosticCheck {
                                        DiagnosticCheckResult(
                                            status = selectedRouteStatus,
                                            details = "selected route ${selectedRouteStatus.label}",
                                        )
                                    },
                            ),
                        nanoTime = { 0L },
                    ),
            )

        assertEquals("0 of 7 checks complete", viewModel.state.value.completionSummary)

        viewModel.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.SelectedRoute))

        assertEquals(
            DiagnosticResultStatus.Passed.label,
            viewModel
                .state
                .value
                .items
                .single { item -> item.type == DiagnosticCheckType.SelectedRoute }
                .status,
        )

        selectedRouteStatus = DiagnosticResultStatus.Failed
        viewModel.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.SelectedRoute))

        assertEquals(
            DiagnosticResultStatus.Failed.label,
            viewModel
                .state
                .value
                .items
                .single { item -> item.type == DiagnosticCheckType.SelectedRoute }
                .status,
        )
    }

    @Test
    fun `view model can expose optimistic running state before checks complete`() {
        val viewModel =
            DiagnosticsViewModel(
                suiteController =
                    DiagnosticsSuiteController(
                        checks = emptyMap(),
                        nanoTime = { 0L },
                    ),
            )

        viewModel.markRunning(setOf(DiagnosticCheckType.RootAvailability))

        assertEquals(
            DiagnosticResultStatus.Running.label,
            viewModel
                .state
                .value
                .items
                .single { item -> item.type == DiagnosticCheckType.RootAvailability }
                .status,
        )
    }

    @Test
    fun `view model preserves queued optimistic running state when another event completes first`() {
        val viewModel =
            DiagnosticsViewModel(
                suiteController =
                    DiagnosticsSuiteController(
                        checks = emptyMap(),
                        nanoTime = { 0L },
                    ),
            )

        viewModel.markRunning(setOf(DiagnosticCheckType.RootAvailability))
        viewModel.handle(DiagnosticsScreenEvent.Refresh)

        assertEquals(
            DiagnosticResultStatus.Running.label,
            viewModel
                .state
                .value
                .items
                .single { item -> item.type == DiagnosticCheckType.RootAvailability }
                .status,
        )
    }

    @Test
    fun `view model exposes one-shot controller effects without retaining them`() {
        val viewModel =
            DiagnosticsViewModel(
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
            )

        viewModel.handle(DiagnosticsScreenEvent.RunCheck(DiagnosticCheckType.LocalManagementApi))
        viewModel.handle(DiagnosticsScreenEvent.CopyCheck(DiagnosticCheckType.LocalManagementApi))

        assertTrue(viewModel.consumeEffects().single() is DiagnosticsScreenEffect.CopyText)
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}
