package com.cellularproxy.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.cellularproxy.app.R
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SecureRandomSensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.service.CellularProxyForegroundService
import com.cellularproxy.app.service.ForegroundServiceActions
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class CellularProxyActivity : Activity() {
    private val settingsRepository by lazy {
        CellularProxyPlainConfigStore.repository(this)
    }
    private val sensitiveRepository by lazy {
        SensitiveConfigRepositoryFactory.create(this)
    }
    private val sensitiveConfigGenerator by lazy {
        SecureRandomSensitiveConfigGenerator()
    }
    private val settingsWorker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ScrollView(this).apply {
                addView(createContentView())
            },
        )
    }

    override fun onDestroy() {
        destroyed = true
        settingsWorker.shutdownNow()
        super.onDestroy()
    }

    private fun createContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val spacing = (12 * density).toInt()
        val initialSettings = ProxySettingsFormState.from(AppConfig.default())

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(
                TextView(context).apply {
                    text = getString(R.string.dashboard_title)
                    textSize = 28f
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.dashboard_summary)
                    textSize = 16f
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            val endpointLabel = TextView(context).apply {
                text = getString(
                    R.string.dashboard_current_endpoint,
                    initialSettings.listenHost,
                    initialSettings.listenPort,
                )
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL
            }
            addView(
                endpointLabel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            val listenHostInput = addLabeledTextInput(
                label = getString(R.string.settings_listen_host),
                value = initialSettings.listenHost,
                topMargin = spacing * 2,
            ).apply { isEnabled = false }
            val listenPortInput = addLabeledTextInput(
                label = getString(R.string.settings_listen_port),
                value = initialSettings.listenPort,
                topMargin = spacing,
            ).apply { isEnabled = false }
            val proxyAuthInput = CheckBox(context).apply {
                text = getString(R.string.settings_proxy_auth_enabled)
                isChecked = initialSettings.authEnabled
                isEnabled = false
            }
            addView(
                proxyAuthInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            val proxyUsernameInput = addLabeledTextInput(
                label = getString(R.string.settings_proxy_username),
                value = "",
                topMargin = spacing,
            ).apply { isEnabled = false }
            val proxyPasswordInput = addLabeledTextInput(
                label = getString(R.string.settings_proxy_password),
                value = "",
                topMargin = spacing,
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isEnabled = false
            }
            val routeInput = addRouteSpinner(
                selectedRoute = initialSettings.route,
                topMargin = spacing,
            ).apply { isEnabled = false }
            val saveButton = Button(context).apply {
                text = getString(R.string.settings_save)
                isEnabled = false
                setOnClickListener {
                    saveSettings(
                        endpointLabel = endpointLabel,
                        listenHostInput = listenHostInput,
                        listenPortInput = listenPortInput,
                        proxyAuthInput = proxyAuthInput,
                        proxyUsernameInput = proxyUsernameInput,
                        proxyPasswordInput = proxyPasswordInput,
                        routeInput = routeInput,
                        saveButton = this,
                        startButton = null,
                        startAfterSave = false,
                    )
                }
            }
            addView(
                saveButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing * 2),
            )
            val startButton = Button(context).apply {
                text = getString(R.string.dashboard_start_proxy)
                isEnabled = false
                setOnClickListener {
                    saveSettings(
                        endpointLabel = endpointLabel,
                        listenHostInput = listenHostInput,
                        listenPortInput = listenPortInput,
                        proxyAuthInput = proxyAuthInput,
                        proxyUsernameInput = proxyUsernameInput,
                        proxyPasswordInput = proxyPasswordInput,
                        routeInput = routeInput,
                        saveButton = saveButton,
                        startButton = this,
                        startAfterSave = true,
                    )
                }
            }
            addView(
                startButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            addView(
                Button(context).apply {
                    text = getString(R.string.dashboard_stop_proxy)
                    setOnClickListener {
                        startService(commandIntent(ForegroundServiceActions.STOP_PROXY))
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            loadSettingsAsync(
                initialSettings = initialSettings,
                endpointLabel = endpointLabel,
                listenHostInput = listenHostInput,
                listenPortInput = listenPortInput,
                proxyAuthInput = proxyAuthInput,
                proxyUsernameInput = proxyUsernameInput,
                proxyPasswordInput = proxyPasswordInput,
                routeInput = routeInput,
                saveButton = saveButton,
                startButton = startButton,
            )
        }
    }

    private fun LinearLayout.addLabeledTextInput(
        label: String,
        value: String,
        topMargin: Int,
    ): EditText {
        addView(
            TextView(context).apply {
                text = label
                textSize = 14f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).withTopMargin(topMargin),
        )
        val input = EditText(context).apply {
            setText(value)
            setSingleLine(true)
        }
        addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return input
    }

    private fun LinearLayout.addRouteSpinner(
        selectedRoute: RouteTarget,
        topMargin: Int,
    ): Spinner {
        addView(
            TextView(context).apply {
                text = getString(R.string.settings_route_policy)
                textSize = 14f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).withTopMargin(topMargin),
        )
        val routes = RouteTarget.entries
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                routes.map(RouteTarget::displayLabel),
            ).also { adapter ->
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(routes.indexOf(selectedRoute).coerceAtLeast(0))
        }
        addView(
            spinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return spinner
    }

    private fun loadSettingsAsync(
        initialSettings: ProxySettingsFormState,
        endpointLabel: TextView,
        listenHostInput: EditText,
        listenPortInput: EditText,
        proxyAuthInput: CheckBox,
        proxyUsernameInput: EditText,
        proxyPasswordInput: EditText,
        routeInput: Spinner,
        saveButton: Button,
        startButton: Button,
    ) {
        settingsWorker.execute {
            val loaded = runCatching {
                ProxySettingsFormState.from(runBlocking { settingsRepository.load() })
            }.getOrNull() ?: return@execute
            runOnLiveActivityUiThread {
                endpointLabel.text = getString(
                    R.string.dashboard_current_endpoint,
                    loaded.listenHost,
                    loaded.listenPort,
                )
                listenHostInput.setText(loaded.listenHost)
                listenPortInput.setText(loaded.listenPort)
                proxyAuthInput.isChecked = loaded.authEnabled
                routeInput.setSelection(RouteTarget.entries.indexOf(loaded.route).coerceAtLeast(0))
                listenHostInput.isEnabled = true
                listenPortInput.isEnabled = true
                proxyAuthInput.isEnabled = true
                proxyUsernameInput.isEnabled = true
                proxyPasswordInput.isEnabled = true
                routeInput.isEnabled = true
                saveButton.isEnabled = true
                startButton.isEnabled = true
            }
        }
    }

    private fun saveSettings(
        endpointLabel: TextView,
        listenHostInput: EditText,
        listenPortInput: EditText,
        proxyAuthInput: CheckBox,
        proxyUsernameInput: EditText,
        proxyPasswordInput: EditText,
        routeInput: Spinner,
        saveButton: Button,
        startButton: Button?,
        startAfterSave: Boolean,
    ) {
        saveButton.isEnabled = false
        startButton?.isEnabled = false
        val route = RouteTarget.entries[routeInput.selectedItemPosition.coerceIn(RouteTarget.entries.indices)]
        val form = ProxySettingsFormState(
            listenHost = listenHostInput.text.toString(),
            listenPort = listenPortInput.text.toString(),
            authEnabled = proxyAuthInput.isChecked,
            route = route,
            proxyUsername = proxyUsernameInput.text.toString(),
            proxyPassword = proxyPasswordInput.text.toString(),
        )
        settingsWorker.execute {
            val controller = ProxySettingsFormController(
                loadConfig = { runBlocking { settingsRepository.load() } },
                saveConfig = { config -> runBlocking { settingsRepository.save(config) } },
                loadSensitiveConfig = { loadOrCreateSensitiveConfig() },
                saveSensitiveConfig = sensitiveRepository::save,
            )
            val result = runCatching {
                controller.save(form)
            }
            runOnLiveActivityUiThread {
                result
                    .onSuccess { saveResult ->
                            handleSettingsSaveResult(
                                endpointLabel = endpointLabel,
                                result = saveResult,
                                proxyUsernameInput = proxyUsernameInput,
                                proxyPasswordInput = proxyPasswordInput,
                                saveButton = saveButton,
                                startButton = startButton,
                                startAfterSave = startAfterSave,
                            )
                        }
                    .onFailure {
                        Toast.makeText(
                            this,
                            getString(R.string.settings_save_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                            saveButton.isEnabled = true
                            startButton?.isEnabled = true
                        }
            }
        }
    }

    private fun handleSettingsSaveResult(
        endpointLabel: TextView,
        result: ProxySettingsSaveResult,
        proxyUsernameInput: EditText,
        proxyPasswordInput: EditText,
        saveButton: Button,
        startButton: Button?,
        startAfterSave: Boolean,
    ) {
        when (result) {
            is ProxySettingsSaveResult.Invalid -> {
                val message = if (result.invalidProxyCredential) {
                    getString(R.string.settings_invalid_proxy_credential)
                } else {
                    getString(R.string.settings_invalid)
                }
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_SHORT,
                ).show()
                saveButton.isEnabled = true
                startButton?.isEnabled = true
            }
            is ProxySettingsSaveResult.Saved -> {
                endpointLabel.text = getString(
                    R.string.dashboard_current_endpoint,
                    result.config.proxy.listenHost,
                    result.config.proxy.listenPort.toString(),
                )
                val message = if (ProxySettingsFormWarning.BroadUnauthenticatedProxy in result.warnings) {
                    getString(R.string.settings_saved_with_broad_auth_warning)
                } else {
                    getString(R.string.settings_saved)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                proxyUsernameInput.setText("")
                proxyPasswordInput.setText("")
                if (startAfterSave) {
                    startForegroundService(commandIntent(ForegroundServiceActions.START_PROXY))
                }
                saveButton.isEnabled = true
                startButton?.isEnabled = true
            }
        }
    }

    private fun loadOrCreateSensitiveConfig(): SensitiveConfig =
        when (val result = sensitiveRepository.load()) {
            is SensitiveConfigLoadResult.Loaded -> result.config
            SensitiveConfigLoadResult.MissingRequiredSecrets -> sensitiveConfigGenerator
                .generateDefaultSensitiveConfig()
                .also(sensitiveRepository::save)
            is SensitiveConfigLoadResult.Invalid -> error("Sensitive config is invalid: ${result.reason}")
        }

    private fun commandIntent(action: String): Intent =
        Intent(this, CellularProxyForegroundService::class.java).setAction(action)

    private fun runOnLiveActivityUiThread(action: () -> Unit) {
        runOnUiThread {
            if (!destroyed) {
                action()
            }
        }
    }
}

private fun LinearLayout.LayoutParams.withTopMargin(topMarginPx: Int): LinearLayout.LayoutParams =
    apply {
        topMargin = topMarginPx
    }

private val RouteTarget.displayLabel: String
    get() = when (this) {
        RouteTarget.WiFi -> "Wi-Fi"
        RouteTarget.Cellular -> "Cellular"
        RouteTarget.Vpn -> "VPN"
        RouteTarget.Automatic -> "Automatic"
    }
