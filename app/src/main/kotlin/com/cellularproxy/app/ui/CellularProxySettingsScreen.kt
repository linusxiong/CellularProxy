@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cellularproxy.app.R
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.viewmodel.SettingsViewModel
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor

@Composable
internal fun CellularProxySettingsRoute(
    initialConfigProvider: () -> AppConfig = AppConfig::default,
    saveConfig: (AppConfig) -> Unit = {},
    observedNetworksProvider: () -> List<NetworkDescriptor> = { emptyList() },
    selectedLanguageProvider: () -> SettingsLanguageOption = { SettingsLanguageOption.System },
    onLanguageChange: (SettingsLanguageOption) -> Unit = {},
    loadSensitiveConfig: (() -> SensitiveConfig)? = null,
    loadSensitiveConfigResult: (() -> SensitiveConfigLoadResult)? = null,
    saveSensitiveConfig: ((SensitiveConfig) -> Unit)? = null,
    onRecordSettingsAuditAction: (PersistedLogsAuditRecord) -> Unit = {},
) {
    val currentInitialConfigProvider by rememberUpdatedState(initialConfigProvider)
    val currentSaveConfig by rememberUpdatedState(saveConfig)
    val currentLoadSensitiveConfig by rememberUpdatedState(loadSensitiveConfig)
    val currentLoadSensitiveConfigResult by rememberUpdatedState(loadSensitiveConfigResult)
    val currentSaveSensitiveConfig by rememberUpdatedState(saveSensitiveConfig)
    val currentOnRecordSettingsAuditAction by rememberUpdatedState(onRecordSettingsAuditAction)
    val observedConfig = initialConfigProvider()
    val availableRoutes = availableRouteTargetsFromObservedNetworks(observedNetworksProvider())
    val settingsViewModel =
        viewModel<SettingsViewModel>(
            factory =
                remember {
                    SettingsViewModelFactory(
                        initialConfigProvider = { currentInitialConfigProvider() },
                        formController =
                            ProxySettingsFormController(
                                loadConfig = { currentInitialConfigProvider() },
                                saveConfig = { config -> currentSaveConfig(config) },
                                loadSensitiveConfigProvider = { currentLoadSensitiveConfig },
                                loadSensitiveConfigResultProvider = { currentLoadSensitiveConfigResult },
                                saveSensitiveConfigProvider = { currentSaveSensitiveConfig },
                            ),
                        auditActionsEnabled = true,
                    )
                },
        )
    val screenState by settingsViewModel.state.collectAsStateWithLifecycle()
    val dispatchEvent: (ProxySettingsScreenEvent) -> Unit = { event ->
        settingsViewModel.handle(event)
        settingsViewModel.consumeEffects().forEach { effect ->
            when (effect) {
                is ProxySettingsScreenEffect.RecordAuditAction -> currentOnRecordSettingsAuditAction(effect.record)
                is ProxySettingsScreenEffect.SaveInvalid,
                is ProxySettingsScreenEffect.SaveSucceeded,
                -> Unit
            }
        }
    }
    LaunchedEffect(observedConfig) {
        dispatchEvent(ProxySettingsScreenEvent.Refresh)
    }

    CellularProxySettingsScreen(
        state = screenState,
        saveEnabled = true,
        onFormChange = { updatedForm -> dispatchEvent(ProxySettingsScreenEvent.UpdateForm(updatedForm)) },
        onSaveSettings = { dispatchEvent(ProxySettingsScreenEvent.SaveChanges) },
        onDiscardChanges = { dispatchEvent(ProxySettingsScreenEvent.DiscardChanges) },
        availableRoutes = availableRoutes,
        selectedLanguage = selectedLanguageProvider(),
        onLanguageChange = onLanguageChange,
    )
}

private class SettingsViewModelFactory(
    private val initialConfigProvider: () -> AppConfig,
    private val formController: ProxySettingsFormController,
    private val auditActionsEnabled: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
        initialConfigProvider = initialConfigProvider,
        formController = formController,
        auditActionsEnabled = auditActionsEnabled,
    ) as T
}

@Composable
internal fun CellularProxySettingsScreen(
    form: ProxySettingsFormState = ProxySettingsFormState.from(AppConfig.default()),
    persistedForm: ProxySettingsFormState = form,
    state: ProxySettingsScreenState =
        ProxySettingsScreenState.from(
            form = form,
            persistedForm = persistedForm,
        ),
    saveEnabled: Boolean = false,
    onFormChange: (ProxySettingsFormState) -> Unit = {},
    onSaveSettings: () -> Unit = {},
    onDiscardChanges: () -> Unit = {},
    availableRoutes: List<RouteTarget> = RouteTarget.entries,
    selectedLanguage: SettingsLanguageOption = SettingsLanguageOption.System,
    onLanguageChange: (SettingsLanguageOption) -> Unit = {},
) {
    val currentForm = state.form

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(stringResource(R.string.settings_section_appearance)) {
            SettingsLanguageSelector(
                selectedLanguage = selectedLanguage,
                onLanguageChange = onLanguageChange,
            )
        }

        SettingsSection(stringResource(R.string.settings_section_proxy)) {
            SettingsTextField(
                label = stringResource(R.string.settings_listen_host),
                value = currentForm.listenHost,
                onValueChange = { value -> onFormChange(currentForm.copy(listenHost = value)) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_listen_port),
                value = currentForm.listenPort,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(currentForm.copy(listenPort = value)) },
            )
            SettingsSwitchRow(
                label = stringResource(R.string.settings_proxy_auth_enabled),
                checked = currentForm.authEnabled,
                onCheckedChange = { checked -> onFormChange(currentForm.copy(authEnabled = checked)) },
            )
            if (currentForm.authEnabled) {
                Text(
                    text = stringResource(R.string.settings_proxy_credential_status, state.proxyCredentialStatus.label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingsTextField(
                    label = stringResource(R.string.settings_proxy_username),
                    value = currentForm.proxyUsername,
                    onValueChange = { value -> onFormChange(currentForm.copy(proxyUsername = value)) },
                )
                SettingsTextField(
                    label = stringResource(R.string.settings_proxy_password),
                    value = currentForm.proxyPassword,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = { value -> onFormChange(currentForm.copy(proxyPassword = value)) },
                )
            }
            SettingsTextField(
                label = stringResource(R.string.settings_max_concurrent_connections),
                value = currentForm.maxConcurrentConnections,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(currentForm.copy(maxConcurrentConnections = value)) },
            )
            SettingsRouteSelector(
                selectedRoute = currentForm.route,
                availableRoutes = availableRoutes,
                onRouteChange = { route -> onFormChange(currentForm.copy(route = route)) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_rotation_root)) {
            SettingsSwitchRow(
                label = stringResource(R.string.settings_strict_ip_change_required),
                checked = currentForm.strictIpChangeRequired,
                onCheckedChange = { checked -> onFormChange(currentForm.copy(strictIpChangeRequired = checked)) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_mobile_data_off_delay_seconds),
                value = currentForm.mobileDataOffDelaySeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(currentForm.copy(mobileDataOffDelaySeconds = value)) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_network_return_timeout_seconds),
                value = currentForm.networkReturnTimeoutSeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(currentForm.copy(networkReturnTimeoutSeconds = value)) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_rotation_cooldown_seconds),
                value = currentForm.cooldownSeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(currentForm.copy(cooldownSeconds = value)) },
            )
            SettingsSwitchRow(
                label = stringResource(R.string.settings_root_operations_enabled),
                checked = currentForm.rootOperationsEnabled,
                onCheckedChange = { checked -> onFormChange(currentForm.copy(rootOperationsEnabled = checked)) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_secrets)) {
            Text(
                text = stringResource(R.string.settings_management_api_token_help),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_management_api_token_status, state.managementApiTokenStatus.label),
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsTextField(
                label = stringResource(R.string.settings_management_api_token),
                value = currentForm.managementApiToken,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { value -> onFormChange(currentForm.copy(managementApiToken = value)) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_cloudflare)) {
            SettingsSwitchRow(
                label = stringResource(R.string.settings_cloudflare_enabled),
                checked = currentForm.cloudflareEnabled,
                onCheckedChange = { checked -> onFormChange(currentForm.copy(cloudflareEnabled = checked)) },
            )
            Text(
                text = stringResource(R.string.settings_tunnel_token_status, state.cloudflareTokenStatus.label),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (currentForm.cloudflareEnabled) {
                Text(
                    text = stringResource(R.string.settings_secret_blank_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingsTextField(
                    label = stringResource(R.string.settings_cloudflare_tunnel_token),
                    value = currentForm.cloudflareTunnelToken,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = { value -> onFormChange(currentForm.copy(cloudflareTunnelToken = value)) },
                )
                SettingsTextField(
                    label = stringResource(R.string.settings_cloudflare_hostname_label),
                    value = currentForm.cloudflareHostnameLabel,
                    onValueChange = { value -> onFormChange(currentForm.copy(cloudflareHostnameLabel = value)) },
                )
            }
        }

        SettingsWarnings(state.warnings)

        SettingsValidationErrors(state.validationErrors)

        Button(
            onClick = onSaveSettings,
            enabled = saveEnabled && ProxySettingsScreenAction.SaveChanges in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_save))
        }
        OutlinedButton(
            onClick = onDiscardChanges,
            enabled = saveEnabled && ProxySettingsScreenAction.DiscardChanges in state.availableActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_discard_changes))
        }
    }
}

@Composable
private fun SettingsWarnings(warnings: Set<ProxySettingsFormWarning>) {
    if (warnings.isEmpty()) {
        return
    }

    SettingsSection("Settings warnings") {
        warnings
            .map(ProxySettingsFormWarning::displayText)
            .forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun SettingsValidationErrors(errors: Set<ProxySettingsValidationError>) {
    if (errors.isEmpty()) {
        return
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        errors
            .map(ProxySettingsValidationError::displayText)
            .forEach { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

private fun ProxySettingsValidationError.displayText(): String = when (this) {
    ProxySettingsValidationError.InvalidListenHost -> "Listen host must be a valid bind address."
    ProxySettingsValidationError.InvalidListenPort -> "Listen port must be between 1 and 65535."
    ProxySettingsValidationError.InvalidMaxConcurrentConnections -> "Max concurrent connections must be greater than zero."
    ProxySettingsValidationError.InvalidRotationTiming -> "Rotation timing values must be whole seconds greater than zero."
    ProxySettingsValidationError.InvalidProxyCredential -> "Enter both proxy username and password, or leave both blank."
    ProxySettingsValidationError.InvalidManagementApiToken -> "Management API token cannot be blank or padded with spaces."
    ProxySettingsValidationError.InvalidCloudflareTunnelToken -> "Cloudflare tunnel token is missing or invalid."
    ProxySettingsValidationError.InvalidSensitiveConfiguration -> "Sensitive configuration is invalid. Discard or update the affected secrets before saving."
}

private fun ProxySettingsFormWarning.displayText(): String = when (this) {
    ProxySettingsFormWarning.BroadUnauthenticatedProxy -> "Broad unauthenticated proxy listener risk."
    ProxySettingsFormWarning.CloudflareEnabledMissingTunnelToken ->
        "Cloudflare is enabled but the tunnel token is missing."
    ProxySettingsFormWarning.CloudflareEnabledInvalidTunnelToken ->
        "Cloudflare is enabled but the tunnel token is invalid."
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(label)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun SettingsLanguageSelector(
    selectedLanguage: SettingsLanguageOption,
    onLanguageChange: (SettingsLanguageOption) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsLanguageOption.entries.forEach { option ->
                FilterChip(
                    selected = option == selectedLanguage,
                    onClick = { onLanguageChange(option) },
                    label = {
                        Text(option.localizedLabel())
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsLanguageOption.localizedLabel(): String = when (this) {
    SettingsLanguageOption.System -> stringResource(R.string.language_system)
    SettingsLanguageOption.English -> stringResource(R.string.language_english)
    SettingsLanguageOption.ChineseSimplified -> stringResource(R.string.language_chinese_simplified)
}

@Composable
private fun SettingsRouteSelector(
    selectedRoute: RouteTarget,
    availableRoutes: List<RouteTarget>,
    onRouteChange: (RouteTarget) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_route_policy),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableRoutes.forEach { route ->
                FilterChip(
                    selected = route == selectedRoute,
                    onClick = { onRouteChange(route) },
                    label = {
                        Text(route.localizedLabel())
                    },
                )
            }
        }
    }
}

internal enum class SettingsLanguageOption(
    val localeTag: String?,
) {
    System(localeTag = null),
    English(localeTag = "en"),
    ChineseSimplified(localeTag = "zh-CN"),
    ;

    companion object {
        fun fromTag(tag: String?): SettingsLanguageOption = entries.firstOrNull { option -> option.localeTag == tag?.takeIf(String::isNotBlank) } ?: System
    }
}

@Composable
private fun RouteTarget.localizedLabel(): String = when (this) {
    RouteTarget.Automatic -> stringResource(R.string.route_automatic)
    RouteTarget.WiFi -> stringResource(R.string.route_wifi)
    RouteTarget.Cellular -> stringResource(R.string.route_cellular)
    RouteTarget.Vpn -> stringResource(R.string.route_vpn)
}

internal fun availableRouteTargetsFromObservedNetworks(networks: List<NetworkDescriptor>): List<RouteTarget> {
    val availableCategories =
        networks
            .filter(NetworkDescriptor::isAvailable)
            .map(NetworkDescriptor::category)
            .toSet()
    return buildList {
        add(RouteTarget.Automatic)
        if (NetworkCategory.WiFi in availableCategories) add(RouteTarget.WiFi)
        if (NetworkCategory.Cellular in availableCategories) add(RouteTarget.Cellular)
        if (NetworkCategory.Vpn in availableCategories) add(RouteTarget.Vpn)
    }
}
