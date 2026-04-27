@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget

@Composable
internal fun CellularProxySettingsRoute() {
    var form by remember { mutableStateOf(ProxySettingsFormState.from(AppConfig.default())) }

    CellularProxySettingsScreen(
        form = form,
        saveEnabled = true,
        onFormChange = { updatedForm -> form = updatedForm },
    )
}

@Composable
internal fun CellularProxySettingsScreen(
    form: ProxySettingsFormState = ProxySettingsFormState.from(AppConfig.default()),
    saveEnabled: Boolean = false,
    onFormChange: (ProxySettingsFormState) -> Unit = {},
    onSaveSettings: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        SettingsSection("Proxy") {
            SettingsTextField(
                label = "Listen host",
                value = form.listenHost,
                onValueChange = { value -> onFormChange(form.copy(listenHost = value)) },
            )
            SettingsTextField(
                label = "Listen port",
                value = form.listenPort,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(form.copy(listenPort = value)) },
            )
            SettingsSwitchRow(
                label = "Proxy authentication",
                checked = form.authEnabled,
                onCheckedChange = { checked -> onFormChange(form.copy(authEnabled = checked)) },
            )
            SettingsTextField(
                label = "Max concurrent connections",
                value = form.maxConcurrentConnections,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(form.copy(maxConcurrentConnections = value)) },
            )
            SettingsRouteSelector(
                selectedRoute = form.route,
                onRouteChange = { route -> onFormChange(form.copy(route = route)) },
            )
        }

        SettingsSection("Rotation And Root") {
            SettingsSwitchRow(
                label = "Strict IP change",
                checked = form.strictIpChangeRequired,
                onCheckedChange = { checked -> onFormChange(form.copy(strictIpChangeRequired = checked)) },
            )
            SettingsTextField(
                label = "Mobile data off delay",
                value = form.mobileDataOffDelaySeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(form.copy(mobileDataOffDelaySeconds = value)) },
            )
            SettingsTextField(
                label = "Network return timeout",
                value = form.networkReturnTimeoutSeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(form.copy(networkReturnTimeoutSeconds = value)) },
            )
            SettingsTextField(
                label = "Rotation cooldown",
                value = form.cooldownSeconds,
                keyboardType = KeyboardType.Number,
                onValueChange = { value -> onFormChange(form.copy(cooldownSeconds = value)) },
            )
            SettingsSwitchRow(
                label = "Root operations",
                checked = form.rootOperationsEnabled,
                onCheckedChange = { checked -> onFormChange(form.copy(rootOperationsEnabled = checked)) },
            )
        }

        SettingsSection("Secrets") {
            Text(
                text = "Leave secret fields blank to keep current values.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsTextField(
                label = "Proxy username",
                value = form.proxyUsername,
                onValueChange = { value -> onFormChange(form.copy(proxyUsername = value)) },
            )
            SettingsTextField(
                label = "Proxy password",
                value = form.proxyPassword,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { value -> onFormChange(form.copy(proxyPassword = value)) },
            )
            SettingsTextField(
                label = "Management API token",
                value = form.managementApiToken,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { value -> onFormChange(form.copy(managementApiToken = value)) },
            )
        }

        SettingsSection("Cloudflare") {
            SettingsSwitchRow(
                label = "Cloudflare enabled",
                checked = form.cloudflareEnabled,
                onCheckedChange = { checked -> onFormChange(form.copy(cloudflareEnabled = checked)) },
            )
            SettingsTextField(
                label = "Cloudflare tunnel token",
                value = form.cloudflareTunnelToken,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { value -> onFormChange(form.copy(cloudflareTunnelToken = value)) },
            )
            SettingsTextField(
                label = "Cloudflare hostname",
                value = form.cloudflareHostnameLabel,
                onValueChange = { value -> onFormChange(form.copy(cloudflareHostnameLabel = value)) },
            )
        }

        Button(
            onClick = onSaveSettings,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save settings")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsRouteSelector(
    selectedRoute: RouteTarget,
    onRouteChange: (RouteTarget) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Default route",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RouteTarget.entries.forEach { route ->
                FilterChip(
                    selected = route == selectedRoute,
                    onClick = { onRouteChange(route) },
                    label = {
                        Text(route.name)
                    },
                )
            }
        }
    }
}
