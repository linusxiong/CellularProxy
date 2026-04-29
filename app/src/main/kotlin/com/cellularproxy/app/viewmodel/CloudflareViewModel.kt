package com.cellularproxy.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cellularproxy.app.ui.CloudflareScreenAction
import com.cellularproxy.app.ui.CloudflareScreenController
import com.cellularproxy.app.ui.CloudflareScreenEffect
import com.cellularproxy.app.ui.CloudflareScreenEvent
import com.cellularproxy.app.ui.CloudflareScreenState
import com.cellularproxy.app.ui.CloudflareTokenStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CloudflareViewModel(
    configProvider: () -> AppConfig = AppConfig::default,
    tunnelStatusProvider: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() },
    tokenStatusProvider: () -> CloudflareTokenStatus = {
        if (configProvider().cloudflare.tunnelTokenPresent) {
            CloudflareTokenStatus.Present
        } else {
            CloudflareTokenStatus.Missing
        }
    },
    edgeSessionSummaryProvider: () -> String? = { null },
    managementApiRoundTripProvider: () -> String? = { null },
    managementApiRoundTripVersionProvider: () -> Long = { 0L },
    secretsProvider: () -> LogRedactionSecrets = { LogRedactionSecrets() },
    auditActionsEnabled: Boolean = false,
    auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
    actionHandler: (CloudflareScreenAction) -> Unit = {},
) : ViewModel() {
    private val controller =
        CloudflareScreenController(
            configProvider = configProvider,
            tunnelStatusProvider = tunnelStatusProvider,
            tokenStatusProvider = tokenStatusProvider,
            edgeSessionSummaryProvider = edgeSessionSummaryProvider,
            managementApiRoundTripProvider = managementApiRoundTripProvider,
            managementApiRoundTripVersionProvider = managementApiRoundTripVersionProvider,
            secretsProvider = secretsProvider,
            auditActionsEnabled = auditActionsEnabled,
            auditOccurredAtEpochMillisProvider = auditOccurredAtEpochMillisProvider,
            actionHandler = actionHandler,
        )
    private val mutableState = MutableStateFlow(controller.state)

    val state: StateFlow<CloudflareScreenState> = mutableState.asStateFlow()

    fun handle(event: CloudflareScreenEvent) {
        controller.handle(event)
        mutableState.value = controller.state
    }

    fun consumeEffects(): List<CloudflareScreenEffect> = controller.consumeEffects()
}
