package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.ui.LogsAuditScreenCategory
import com.cellularproxy.app.ui.LogsAuditScreenEffect
import com.cellularproxy.app.ui.LogsAuditScreenEvent
import com.cellularproxy.app.ui.LogsAuditScreenInputRow
import com.cellularproxy.app.ui.LogsAuditScreenSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogsAuditViewModelTest {
    @Test
    fun `view model exposes logs audit state as state flow and updates after refresh`() {
        var rows = listOf(logsAuditInputRow(id = "initial", title = "Initial"))
        val viewModel =
            LogsAuditViewModel(
                rowsProvider = { rows },
                exportSupported = true,
                exportGeneratedAtEpochMillisProvider = { 123L },
            )

        assertEquals(
            listOf("initial"),
            viewModel.state.value.rows
                .map { row -> row.id },
        )

        rows = listOf(logsAuditInputRow(id = "updated", title = "Updated"))
        viewModel.handle(LogsAuditScreenEvent.Refresh)

        assertEquals(
            listOf("updated"),
            viewModel.state.value.rows
                .map { row -> row.id },
        )
        assertEquals(
            123L,
            viewModel.state.value.exportBundle
                ?.generatedAtEpochMillis,
        )
    }

    @Test
    fun `view model exposes one-shot controller effects without retaining them`() {
        val viewModel =
            LogsAuditViewModel(
                rowsProvider = { listOf(logsAuditInputRow(id = "row-1", title = "Row 1")) },
                exportSupported = true,
            )

        viewModel.handle(LogsAuditScreenEvent.CopyFilteredSummary)

        assertTrue(viewModel.consumeEffects().single() is LogsAuditScreenEffect.CopyText)
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}

private fun logsAuditInputRow(
    id: String,
    title: String,
): LogsAuditScreenInputRow = LogsAuditScreenInputRow(
    id = id,
    category = LogsAuditScreenCategory.AppRuntime,
    severity = LogsAuditScreenSeverity.Info,
    occurredAtEpochMillis = 1L,
    title = title,
    detail = "detail",
)
