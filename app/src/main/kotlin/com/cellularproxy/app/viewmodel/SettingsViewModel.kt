package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.ui.ProxySettingsFormController
import com.cellularproxy.app.ui.ProxySettingsScreenController
import com.cellularproxy.app.ui.ProxySettingsScreenEffect
import com.cellularproxy.app.ui.ProxySettingsScreenEvent
import com.cellularproxy.app.ui.ProxySettingsScreenState
import com.cellularproxy.shared.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SettingsViewModel(
    initialConfigProvider: () -> AppConfig,
    formController: ProxySettingsFormController,
    auditActionsEnabled: Boolean = false,
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val controller =
        ProxySettingsScreenController(
            initialConfigProvider = initialConfigProvider,
            formController = formController,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<ProxySettingsScreenState> = mutableState.asStateFlow()

    fun handle(event: ProxySettingsScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<ProxySettingsScreenEffect> = controller.consumeEffects()
}
