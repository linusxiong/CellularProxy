package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.ui.DashboardScreenAction
import com.cellularproxy.app.ui.DashboardScreenController
import com.cellularproxy.app.ui.DashboardScreenEffect
import com.cellularproxy.app.ui.DashboardScreenEvent
import com.cellularproxy.app.ui.DashboardScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DashboardViewModel(
    statusProvider: () -> DashboardStatusModel,
    auditActionsEnabled: Boolean = false,
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
    actionHandler: (DashboardScreenAction) -> Unit = {},
) : ViewModel() {
    private val controller =
        DashboardScreenController(
            statusProvider = statusProvider,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
            actionHandler = actionHandler,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<DashboardScreenState> = mutableState.asStateFlow()

    fun handle(event: DashboardScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<DashboardScreenEffect> = controller.consumeEffects()
}
