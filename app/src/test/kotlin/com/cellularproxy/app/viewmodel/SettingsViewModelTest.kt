package com.cellularproxy.app.viewmodel

import com.cellularproxy.app.ui.ProxySettingsFormController
import com.cellularproxy.app.ui.ProxySettingsScreenAction
import com.cellularproxy.app.ui.ProxySettingsScreenEffect
import com.cellularproxy.app.ui.ProxySettingsScreenEvent
import com.cellularproxy.app.ui.ProxySettingsValidationError
import com.cellularproxy.shared.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @Test
    fun `view model exposes settings state as state flow and updates after events`() {
        var config = AppConfig.default()
        val viewModel =
            SettingsViewModel(
                initialConfigProvider = { config },
                formController =
                    ProxySettingsFormController(
                        loadConfig = { config },
                        saveConfig = { savedConfig -> config = savedConfig },
                    ),
            )

        val editedForm =
            viewModel.state.value.form
                .copy(listenPort = "9090")
        viewModel.handle(ProxySettingsScreenEvent.UpdateForm(editedForm))

        assertEquals("9090", viewModel.state.value.form.listenPort)
        assertTrue(ProxySettingsScreenAction.SaveChanges in viewModel.state.value.availableActions)

        viewModel.handle(ProxySettingsScreenEvent.SaveChanges)

        assertEquals("9090", viewModel.state.value.persistedForm.listenPort)
        assertEquals(viewModel.state.value.persistedForm, viewModel.state.value.form)
        assertTrue(viewModel.consumeEffects().single() is ProxySettingsScreenEffect.SaveSucceeded)
    }

    @Test
    fun `view model exposes controller validation state and one-shot effects`() {
        val viewModel =
            SettingsViewModel(
                initialConfigProvider = AppConfig::default,
                formController =
                    ProxySettingsFormController(
                        loadConfig = AppConfig::default,
                        saveConfig = {},
                    ),
            )

        viewModel.handle(
            ProxySettingsScreenEvent.UpdateForm(
                viewModel.state.value.form
                    .copy(listenPort = "invalid"),
            ),
        )
        viewModel.handle(ProxySettingsScreenEvent.SaveChanges)

        assertTrue(
            ProxySettingsValidationError.InvalidListenPort in viewModel.state.value.validationErrors,
        )
        assertTrue(viewModel.consumeEffects().single() is ProxySettingsScreenEffect.SaveInvalid)
        assertTrue(viewModel.consumeEffects().isEmpty())
    }
}
