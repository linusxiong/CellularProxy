package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.ui.RotationScreenAction
import com.cellularproxy.app.ui.RotationScreenEffect
import com.cellularproxy.app.ui.RotationScreenEvent
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationViewModelTest {
    @Test
    fun `view model exposes rotation state as state flow and updates after events`() {
        var rotationStatus = RotationStatus.idle()
        val actions = mutableListOf<RotationScreenAction>()
        val viewModel =
            RotationViewModel(
                configProvider = { rootEnabledConfigForViewModelTest() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                actionHandler = { action ->
                    actions += action
                    rotationStatus =
                        RotationStatus(
                            state = RotationState.CheckingRoot,
                            operation = RotationOperation.MobileData,
                        )
                },
            )

        assertEquals("Idle", viewModel.state.value.currentPhase)

        viewModel.handle(RotationScreenEvent.RotateMobileData)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)
        assertEquals("CheckingRoot", viewModel.state.value.currentPhase)
        assertFalse(RotationScreenAction.RotateMobileData in viewModel.state.value.availableActions)
    }

    @Test
    fun `view model exposes one-shot controller effects without retaining them`() {
        val viewModel =
            RotationViewModel(
                configProvider = { rootEnabledConfigForViewModelTest() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
            )

        viewModel.handle(RotationScreenEvent.CopyDiagnostics)

        assertTrue(viewModel.consumeEffects().single() is RotationScreenEffect.CopyText)
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}

private fun rootEnabledConfigForViewModelTest(): AppConfig {
    val defaultConfig = AppConfig.default()
    return defaultConfig.copy(
        root =
            defaultConfig.root.copy(
                operationsEnabled = true,
            ),
    )
}
