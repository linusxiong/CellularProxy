package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.ui.RotationScreenAction
import com.cellularproxy.app.ui.RotationScreenController
import com.cellularproxy.app.ui.RotationScreenEffect
import com.cellularproxy.app.ui.RotationScreenEvent
import com.cellularproxy.app.ui.RotationScreenState
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RotationViewModel(
    configProvider: () -> AppConfig = AppConfig::default,
    rotationStatusProvider: () -> RotationStatus = { RotationStatus.idle() },
    currentPublicIpProvider: () -> String? = { null },
    rootAvailabilityProvider: () -> RootAvailabilityStatus = { RootAvailabilityStatus.Unknown },
    cooldownRemainingSecondsProvider: () -> Long? = { null },
    activeConnectionsProvider: () -> Long = { 0 },
    secretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    auditActionsEnabled: Boolean = false,
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
    actionHandler: (RotationScreenAction) -> Unit = {},
) : ViewModel() {
    private val controller =
        RotationScreenController(
            configProvider = configProvider,
            rotationStatusProvider = rotationStatusProvider,
            currentPublicIpProvider = currentPublicIpProvider,
            rootAvailabilityProvider = rootAvailabilityProvider,
            cooldownRemainingSecondsProvider = cooldownRemainingSecondsProvider,
            activeConnectionsProvider = activeConnectionsProvider,
            secretsProvider = secretsProvider,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
            actionHandler = actionHandler,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<RotationScreenState> = mutableState.asStateFlow()

    fun handle(event: RotationScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<RotationScreenEffect> = controller.consumeEffects()
}
