package com.cellularproxy.app.ui

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationScreenControllerTest {
    @Test
    fun `controller dispatches available rotation actions and refreshes screen state`() {
        var rotationStatus = RotationStatus.idle()
        val actions = mutableListOf<RotationScreenAction>()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                actionHandler = { action ->
                    actions += action
                    rotationStatus =
                        when (action) {
                            RotationScreenAction.RotateMobileData ->
                                RotationStatus(
                                    state = RotationState.CheckingRoot,
                                    operation = RotationOperation.MobileData,
                                )
                            else -> rotationStatus
                        }
                },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)
        assertEquals("CheckingRoot", controller.state.currentPhase)
        assertFalse(RotationScreenAction.RotateAirplaneMode in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses unavailable rotation actions`() {
        val actions = mutableListOf<RotationScreenAction>()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Unavailable },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)

        assertTrue(actions.isEmpty())
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            controller.state.availableActions,
        )
    }

    @Test
    fun `controller suppresses duplicate unsafe rotations until provider state changes`() {
        val actions = mutableListOf<RotationScreenAction>()
        var rotationStatus = RotationStatus.idle()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)
        assertFalse(RotationScreenAction.RotateMobileData in controller.state.availableActions)
        assertFalse(RotationScreenAction.RotateAirplaneMode in controller.state.availableActions)

        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)

        rotationStatus =
            RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.MobileData,
            )
        controller.handle(RotationScreenEvent.Refresh)
        rotationStatus =
            RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.MobileData,
                failureReason = RotationFailureReason.RootUnavailable,
            )
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.RotateMobileData)

        assertEquals(
            listOf(
                RotationScreenAction.RotateMobileData,
                RotationScreenAction.RotateMobileData,
            ),
            actions,
        )
    }

    @Test
    fun `controller exposes pending unsafe rotation action as visible state until resolved`() {
        var rotationStatus = RotationStatus.idle()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
            )

        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals("In progress: Rotate airplane mode", controller.state.pendingOperation)

        rotationStatus =
            RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.AirplaneMode,
            )
        controller.handle(RotationScreenEvent.Refresh)

        assertEquals("In progress: Rotate airplane mode", controller.state.pendingOperation)

        rotationStatus =
            RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.AirplaneMode,
                failureReason = RotationFailureReason.RootUnavailable,
            )
        controller.handle(RotationScreenEvent.Refresh)

        assertEquals("None", controller.state.pendingOperation)
    }

    @Test
    fun `controller emits redacted diagnostics copy effect once`() {
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = {
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.20",
                        publicIpChanged = true,
                    )
                },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                secrets = LogRedactionSecrets(proxyCredential = "203.0.113.20"),
            )

        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertTrue(copyText.contains("Current phase: Completed"))
        assertFalse(copyText.contains("203.0.113.20"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller refreshes diagnostics redaction secrets from provider`() {
        var secrets = LogRedactionSecrets()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = {
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.20",
                        publicIpChanged = true,
                    )
                },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                secretsProvider = { secrets },
            )

        secrets = LogRedactionSecrets(proxyCredential = "203.0.113.20")
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertFalse(copyText.contains("203.0.113.20"))
        assertTrue(copyText.contains("[REDACTED]"))
    }

    @Test
    fun `controller displays current public ip from provider and redacts it in diagnostics copy`() {
        var publicIp: String? = "203.0.113.44"
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                currentPublicIpProvider = { publicIp },
                secretsProvider = { LogRedactionSecrets(proxyCredential = "198.51.100.55") },
            )

        assertEquals("203.0.113.44", controller.state.currentPublicIp)

        publicIp = "198.51.100.55"
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertEquals("[REDACTED]", controller.state.currentPublicIp)
        assertTrue(copyText.contains("Current public IP: [REDACTED]"))
        assertFalse(copyText.contains("198.51.100.55"))
    }

    private fun rootEnabledConfig(): AppConfig {
        val defaultConfig = AppConfig.default()
        return defaultConfig.copy(
            root =
                defaultConfig.root.copy(
                    operationsEnabled = true,
                ),
        )
    }
}
