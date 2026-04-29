package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.cloudflare.CloudflareTunnelToken
import com.cellularproxy.cloudflare.CloudflareTunnelTokenParseResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ProxySettingsFormState(
    val listenHost: String,
    val listenPort: String,
    val authEnabled: Boolean,
    val maxConcurrentConnections: String = "64",
    val route: RouteTarget,
    val strictIpChangeRequired: Boolean = false,
    val mobileDataOffDelaySeconds: String = "3",
    val networkReturnTimeoutSeconds: String = "60",
    val cooldownSeconds: String = "180",
    val rootOperationsEnabled: Boolean = false,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val managementApiToken: String = "",
    val cloudflareEnabled: Boolean = false,
    val cloudflareTunnelToken: String = "",
    val cloudflareHostnameLabel: String = "",
    val proxyCredentialPresent: Boolean = false,
    val managementApiTokenPresent: Boolean = false,
    val cloudflareTunnelTokenPresent: Boolean = false,
    val sensitiveConfigInvalid: Boolean = false,
) {
    fun toAppConfig(base: AppConfig): ProxySettingsFormResult = toSettings(
        base = base,
        sensitiveConfig = null,
    )

    fun toSettings(
        base: AppConfig,
        sensitiveConfig: SensitiveConfig?,
    ): ProxySettingsFormResult {
        val normalizedPort = listenPort.trim()
        val parsedPort = normalizedPort.toStrictPortOrNull()
        val parsedMaxConcurrentConnections = maxConcurrentConnections.trim().toStrictPositiveIntOrNull()
        val parsedMobileDataOffDelay = mobileDataOffDelaySeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val parsedNetworkReturnTimeout = networkReturnTimeoutSeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val parsedCooldown = cooldownSeconds.trim().toStrictPositiveSecondsDurationOrNull()
        val proxyAndRouteCandidate =
            base.copy(
                proxy =
                    ProxyConfig(
                        listenHost = listenHost.trim(),
                        listenPort = parsedPort ?: INVALID_PORT_SENTINEL,
                        authEnabled = authEnabled,
                        maxConcurrentConnections = parsedMaxConcurrentConnections ?: INVALID_POSITIVE_INT_SENTINEL,
                    ),
                network =
                    base.network.copy(
                        defaultRoutePolicy = route,
                    ),
                rotation =
                    base.rotation.copy(
                        strictIpChangeRequired = strictIpChangeRequired,
                        mobileDataOffDelay = parsedMobileDataOffDelay ?: base.rotation.mobileDataOffDelay,
                        networkReturnTimeout = parsedNetworkReturnTimeout ?: base.rotation.networkReturnTimeout,
                        cooldown = parsedCooldown ?: base.rotation.cooldown,
                    ),
                root =
                    base.root.copy(
                        operationsEnabled = rootOperationsEnabled,
                    ),
            )
        val errors =
            buildList {
                if (proxyAndRouteCandidate.validate().errors.contains(ConfigValidationError.InvalidListenHost)) {
                    add(ConfigValidationError.InvalidListenHost)
                }
                if (parsedPort == null) {
                    add(ConfigValidationError.InvalidListenPort)
                }
            }
        if (errors.isNotEmpty()) {
            return ProxySettingsFormResult.Invalid(errors)
        }
        if (parsedMaxConcurrentConnections == null) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidMaxConcurrentConnections = true,
            )
        }
        if (
            parsedMobileDataOffDelay == null ||
            parsedNetworkReturnTimeout == null ||
            parsedCooldown == null
        ) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidRotationTiming = true,
            )
        }

        val updatedSensitiveConfig =
            sensitiveConfig
                ?.withProxyCredentialEdit(
                    username = proxyUsername,
                    password = proxyPassword,
                )?.withManagementApiTokenEdit(managementApiToken)
                ?.withCloudflareTunnelTokenEdit(cloudflareTunnelToken)
                ?: SensitiveConfigEditResult.Valid(null)
        when (updatedSensitiveConfig) {
            SensitiveConfigEditResult.InvalidProxyCredential -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidProxyCredential = true,
                )
            }
            SensitiveConfigEditResult.InvalidManagementApiToken -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidManagementApiToken = true,
                )
            }
            SensitiveConfigEditResult.InvalidCloudflareTunnelToken -> {
                return ProxySettingsFormResult.Invalid(
                    errors = emptyList(),
                    invalidCloudflareTunnelToken = true,
                )
            }
            is SensitiveConfigEditResult.Valid -> Unit
        }
        val appliesSensitiveEdits = sensitiveConfig != null
        val tunnelTokenPresent =
            if (appliesSensitiveEdits) {
                updatedSensitiveConfig.value?.cloudflareTunnelToken?.isNotBlank() == true
            } else {
                base.cloudflare.tunnelTokenPresent
            }
        if (appliesSensitiveEdits && cloudflareEnabled && !tunnelTokenPresent) {
            return ProxySettingsFormResult.Invalid(
                errors = emptyList(),
                invalidCloudflareTunnelToken = true,
            )
        }
        val candidate =
            proxyAndRouteCandidate.copy(
                cloudflare =
                    if (appliesSensitiveEdits) {
                        base.cloudflare.copy(
                            enabled = cloudflareEnabled,
                            tunnelTokenPresent = tunnelTokenPresent,
                            managementHostnameLabel =
                                cloudflareHostnameLabel
                                    .trim()
                                    .takeIf(String::isNotEmpty)
                                    ?.safeCloudflareManagementHostnameLabel()
                                    ?.takeIf(String::isNotEmpty),
                        )
                    } else {
                        base.cloudflare
                    },
            )

        val warnings =
            buildSet {
                if (candidate.proxy.hasHighSecurityRisk) {
                    add(ProxySettingsFormWarning.BroadUnauthenticatedProxy)
                }
            }
        return ProxySettingsFormResult.Valid(
            config = candidate,
            warnings = warnings,
            sensitiveConfig = updatedSensitiveConfig.value,
        )
    }

    fun afterSuccessfulSave(
        savedConfig: AppConfig,
        savedSensitiveConfig: SensitiveConfig? = null,
    ): ProxySettingsFormState = from(savedConfig).withEditedCloudflareFieldsFrom(
        editedForm = this,
        savedSensitiveConfig = savedSensitiveConfig,
    )

    companion object {
        fun from(
            config: AppConfig,
            sensitiveConfig: SensitiveConfig? = null,
            sensitiveConfigInvalid: Boolean = false,
        ): ProxySettingsFormState = ProxySettingsFormState(
            listenHost = config.proxy.listenHost,
            listenPort = config.proxy.listenPort.toString(),
            authEnabled = config.proxy.authEnabled,
            maxConcurrentConnections = config.proxy.maxConcurrentConnections.toString(),
            route = config.network.defaultRoutePolicy,
            strictIpChangeRequired = config.rotation.strictIpChangeRequired,
            mobileDataOffDelaySeconds =
                config.rotation.mobileDataOffDelay.inWholeSeconds
                    .toString(),
            networkReturnTimeoutSeconds =
                config.rotation.networkReturnTimeout.inWholeSeconds
                    .toString(),
            cooldownSeconds =
                config.rotation.cooldown.inWholeSeconds
                    .toString(),
            rootOperationsEnabled = config.root.operationsEnabled,
            cloudflareEnabled = config.cloudflare.enabled,
            cloudflareHostnameLabel = config.cloudflare.managementHostnameLabel.orEmpty(),
            proxyCredentialPresent = sensitiveConfig?.proxyCredential != null,
            managementApiTokenPresent = sensitiveConfig?.managementApiToken?.isNotBlank() == true,
            cloudflareTunnelTokenPresent =
                sensitiveConfig?.cloudflareTunnelToken?.isNotBlank() == true ||
                    config.cloudflare.tunnelTokenPresent,
            sensitiveConfigInvalid = sensitiveConfigInvalid,
        )
    }
}

data class ProxySettingsScreenState(
    val form: ProxySettingsFormState,
    val persistedForm: ProxySettingsFormState,
    val validationErrors: Set<ProxySettingsValidationError>,
    val availableActions: List<ProxySettingsScreenAction>,
    val proxyCredentialStatus: ProxySettingsSecretStatus,
    val managementApiTokenStatus: ProxySettingsSecretStatus,
    val cloudflareTokenStatus: ProxySettingsCloudflareTokenStatus,
    val warnings: Set<ProxySettingsFormWarning>,
) {
    companion object {
        fun from(
            form: ProxySettingsFormState,
            persistedForm: ProxySettingsFormState,
            extraValidationErrors: Set<ProxySettingsValidationError> = emptySet(),
        ): ProxySettingsScreenState {
            val validationErrors = form.validationErrors() + extraValidationErrors
            return ProxySettingsScreenState(
                form = form,
                persistedForm = persistedForm,
                validationErrors = validationErrors,
                availableActions =
                    buildList {
                        if (form != persistedForm && validationErrors.isEmpty()) {
                            add(ProxySettingsScreenAction.SaveChanges)
                        }
                        if (form != persistedForm) {
                            add(ProxySettingsScreenAction.DiscardChanges)
                        }
                    },
                proxyCredentialStatus = form.proxyCredentialStatus(),
                managementApiTokenStatus = form.managementApiTokenStatus(),
                cloudflareTokenStatus = form.cloudflareTokenStatus(),
                warnings = form.warnings(),
            )
        }
    }
}

enum class ProxySettingsCloudflareTokenStatus(
    val label: String,
) {
    Missing("Missing"),
    Present("Present"),
    Edited("Edited"),
    Invalid("Invalid"),
}

enum class ProxySettingsSecretStatus(
    val label: String,
) {
    Missing("Missing"),
    Present("Present"),
    Edited("Edited"),
    Invalid("Invalid"),
}

enum class ProxySettingsScreenAction {
    SaveChanges,
    DiscardChanges,
}

enum class ProxySettingsValidationError {
    InvalidListenHost,
    InvalidListenPort,
    InvalidMaxConcurrentConnections,
    InvalidRotationTiming,
    InvalidProxyCredential,
    InvalidManagementApiToken,
    InvalidCloudflareTunnelToken,
    InvalidSensitiveConfiguration,
}

sealed interface ProxySettingsFormResult {
    data class Valid(
        val config: AppConfig,
        val warnings: Set<ProxySettingsFormWarning>,
        val sensitiveConfig: SensitiveConfig? = null,
    ) : ProxySettingsFormResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
        val invalidProxyCredential: Boolean = false,
        val invalidManagementApiToken: Boolean = false,
        val invalidCloudflareTunnelToken: Boolean = false,
        val invalidMaxConcurrentConnections: Boolean = false,
        val invalidRotationTiming: Boolean = false,
    ) : ProxySettingsFormResult
}

enum class ProxySettingsFormWarning {
    BroadUnauthenticatedProxy,
    CloudflareEnabledMissingTunnelToken,
    CloudflareEnabledInvalidTunnelToken,
}

private fun ProxySettingsFormState.warnings(): Set<ProxySettingsFormWarning> = buildSet {
    if (
        ProxyConfig(
            listenHost = listenHost.trim(),
            listenPort =
                listenPort
                    .trim()
                    .toStrictPortOrNull()
                    ?: INVALID_PORT_SENTINEL,
            authEnabled = authEnabled,
            maxConcurrentConnections =
                maxConcurrentConnections
                    .trim()
                    .toStrictPositiveIntOrNull()
                    ?: INVALID_POSITIVE_INT_SENTINEL,
        ).hasHighSecurityRisk
    ) {
        add(ProxySettingsFormWarning.BroadUnauthenticatedProxy)
    }
    if (cloudflareEnabled && !cloudflareTunnelTokenPresent && cloudflareTunnelToken.isEmpty()) {
        add(ProxySettingsFormWarning.CloudflareEnabledMissingTunnelToken)
    }
    if (cloudflareEnabled && cloudflareTunnelToken.isNotEmpty() && cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit()) {
        add(ProxySettingsFormWarning.CloudflareEnabledInvalidTunnelToken)
    }
}

private fun ProxySettingsFormState.withEditedCloudflareFieldsFrom(
    editedForm: ProxySettingsFormState,
    savedSensitiveConfig: SensitiveConfig?,
): ProxySettingsFormState = copy(
    cloudflareEnabled = editedForm.cloudflareEnabled,
    cloudflareHostnameLabel = editedForm.cloudflareHostnameLabel,
    proxyCredentialPresent =
        proxyCredentialPresent ||
            savedSensitiveConfig?.proxyCredential != null ||
            editedForm.proxyCredentialPresent,
    managementApiTokenPresent =
        managementApiTokenPresent ||
            savedSensitiveConfig?.managementApiToken?.isNotBlank() == true ||
            editedForm.managementApiTokenPresent,
    cloudflareTunnelTokenPresent =
        cloudflareTunnelTokenPresent ||
            savedSensitiveConfig
                ?.cloudflareTunnelToken
                ?.isNotBlank() == true,
)

private fun ProxySettingsFormState.proxyCredentialStatus(): ProxySettingsSecretStatus = when {
    sensitiveConfigInvalid -> ProxySettingsSecretStatus.Invalid
    hasInvalidProxyCredentialEdit() -> ProxySettingsSecretStatus.Invalid
    proxyUsername.isNotEmpty() || proxyPassword.isNotEmpty() -> ProxySettingsSecretStatus.Edited
    proxyCredentialPresent -> ProxySettingsSecretStatus.Present
    else -> ProxySettingsSecretStatus.Missing
}

private fun ProxySettingsFormState.managementApiTokenStatus(): ProxySettingsSecretStatus = when {
    sensitiveConfigInvalid -> ProxySettingsSecretStatus.Invalid
    hasInvalidManagementApiTokenEdit() -> ProxySettingsSecretStatus.Invalid
    managementApiToken.isNotEmpty() -> ProxySettingsSecretStatus.Edited
    managementApiTokenPresent -> ProxySettingsSecretStatus.Present
    else -> ProxySettingsSecretStatus.Missing
}

private fun ProxySettingsFormState.cloudflareTokenStatus(): ProxySettingsCloudflareTokenStatus = when {
    sensitiveConfigInvalid -> ProxySettingsCloudflareTokenStatus.Invalid
    cloudflareTunnelToken.isNotEmpty() && cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit() ->
        ProxySettingsCloudflareTokenStatus.Invalid
    cloudflareTunnelToken.isNotEmpty() -> ProxySettingsCloudflareTokenStatus.Edited
    cloudflareTunnelTokenPresent -> ProxySettingsCloudflareTokenStatus.Present
    else -> ProxySettingsCloudflareTokenStatus.Missing
}

private fun ProxySettingsFormState.requiresSensitiveConfig(base: AppConfig): Boolean = proxyUsername.isNotEmpty() ||
    proxyPassword.isNotEmpty() ||
    managementApiToken.isNotEmpty() ||
    cloudflareTunnelToken.isNotEmpty() ||
    cloudflareEnabled != base.cloudflare.enabled ||
    cloudflareHostnameLabel.trim() != base.cloudflare.managementHostnameLabel.orEmpty()

data class SensitiveConfigDisplayState(
    val sensitiveConfig: SensitiveConfig? = null,
    val sensitiveConfigInvalid: Boolean = false,
)

class ProxySettingsFormController(
    private val loadConfig: () -> AppConfig,
    private val saveConfig: (AppConfig) -> Unit,
    private val loadSensitiveConfig: (() -> SensitiveConfig)? = null,
    private val loadSensitiveConfigResult: (() -> SensitiveConfigLoadResult)? = null,
    private val saveSensitiveConfig: ((SensitiveConfig) -> Unit)? = null,
    private val loadSensitiveConfigProvider: () -> (() -> SensitiveConfig)? = { loadSensitiveConfig },
    private val loadSensitiveConfigResultProvider: () -> (() -> SensitiveConfigLoadResult)? = { loadSensitiveConfigResult },
    private val saveSensitiveConfigProvider: () -> ((SensitiveConfig) -> Unit)? = { saveSensitiveConfig },
) {
    fun loadCurrentConfig(): AppConfig = loadConfig()

    fun loadCurrentSensitiveConfigForDisplay(): SensitiveConfigDisplayState = when (val result = loadSensitiveConfigResultProvider()?.invoke()) {
        is SensitiveConfigLoadResult.Loaded -> SensitiveConfigDisplayState(sensitiveConfig = result.config)
        is SensitiveConfigLoadResult.Invalid -> SensitiveConfigDisplayState(sensitiveConfigInvalid = true)
        SensitiveConfigLoadResult.MissingRequiredSecrets -> SensitiveConfigDisplayState()
        null -> SensitiveConfigDisplayState()
    }

    fun save(form: ProxySettingsFormState): ProxySettingsSaveResult {
        val baseConfig = loadConfig()
        when (val plainResult = form.toAppConfig(base = baseConfig)) {
            is ProxySettingsFormResult.Invalid -> {
                return ProxySettingsSaveResult.Invalid(
                    errors = plainResult.errors,
                    invalidProxyCredential = plainResult.invalidProxyCredential,
                    invalidManagementApiToken = plainResult.invalidManagementApiToken,
                    invalidCloudflareTunnelToken = plainResult.invalidCloudflareTunnelToken,
                    invalidMaxConcurrentConnections = plainResult.invalidMaxConcurrentConnections,
                    invalidRotationTiming = plainResult.invalidRotationTiming,
                )
            }
            is ProxySettingsFormResult.Valid -> Unit
        }
        if (form.hasInvalidProxyCredentialEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidProxyCredential = true,
            )
        }
        if (form.hasInvalidManagementApiTokenEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidManagementApiToken = true,
            )
        }
        if (form.hasInvalidCloudflareTunnelTokenEdit()) {
            return ProxySettingsSaveResult.Invalid(
                errors = emptyList(),
                invalidCloudflareTunnelToken = true,
            )
        }

        val result =
            form.toSettings(
                base = baseConfig,
                sensitiveConfig =
                    if (form.requiresSensitiveConfig(baseConfig)) {
                        when (val loadResult = loadSensitiveConfigForSave()) {
                            is SensitiveConfigLoadResult.Loaded -> loadResult.config
                            SensitiveConfigLoadResult.MissingRequiredSecrets ->
                                form.replacementSensitiveConfigOrNull()
                                    ?: return ProxySettingsSaveResult.Invalid(
                                        errors = emptyList(),
                                        invalidSensitiveConfiguration = true,
                                    )
                            is SensitiveConfigLoadResult.Invalid ->
                                form.replacementSensitiveConfigOrNull()
                                    ?: return ProxySettingsSaveResult.Invalid(
                                        errors = emptyList(),
                                        invalidSensitiveConfiguration = true,
                                    )
                            null -> null
                        }
                    } else {
                        null
                    },
            )
        return when (result) {
            is ProxySettingsFormResult.Invalid ->
                ProxySettingsSaveResult.Invalid(
                    errors = result.errors,
                    invalidProxyCredential = result.invalidProxyCredential,
                    invalidManagementApiToken = result.invalidManagementApiToken,
                    invalidCloudflareTunnelToken = result.invalidCloudflareTunnelToken,
                    invalidMaxConcurrentConnections = result.invalidMaxConcurrentConnections,
                    invalidRotationTiming = result.invalidRotationTiming,
                )
            is ProxySettingsFormResult.Valid -> {
                result.sensitiveConfig?.let { sensitiveConfig ->
                    saveSensitiveConfigProvider()?.invoke(sensitiveConfig)
                        ?: error("Sensitive config save callback is required when sensitive config is loaded")
                }
                saveConfig(result.config)
                ProxySettingsSaveResult.Saved(
                    config = result.config,
                    warnings = result.warnings,
                    sensitiveConfig = result.sensitiveConfig,
                )
            }
        }
    }

    private fun loadSensitiveConfigForSave(): SensitiveConfigLoadResult? = loadSensitiveConfigResultProvider()?.invoke()
        ?: loadSensitiveConfigProvider()?.invoke()?.let(SensitiveConfigLoadResult::Loaded)
}

class ProxySettingsScreenController(
    initialConfigProvider: () -> AppConfig,
    private val formController: ProxySettingsFormController,
    private val auditActionsEnabled: Boolean = false,
    private val auditOccurredAtEpochMillisProvider: () -> Long = System::currentTimeMillis,
) {
    private val pendingEffects = mutableListOf<ProxySettingsScreenEffect>()
    var state: ProxySettingsScreenState =
        initialForm(initialConfigProvider())
            .let { form ->
                ProxySettingsScreenState.from(
                    form = form,
                    persistedForm = form,
                )
            }
        private set

    fun handle(event: ProxySettingsScreenEvent) {
        when (event) {
            is ProxySettingsScreenEvent.UpdateForm -> updateForm(event.form)
            ProxySettingsScreenEvent.DiscardChanges -> updateForm(state.persistedForm)
            ProxySettingsScreenEvent.SaveChanges -> saveChanges()
            ProxySettingsScreenEvent.Refresh -> refreshFromProvider()
        }
    }

    fun consumeEffects(): List<ProxySettingsScreenEffect> {
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        return effects
    }

    private fun updateForm(form: ProxySettingsFormState) {
        state =
            ProxySettingsScreenState.from(
                form = form,
                persistedForm = state.persistedForm,
            )
    }

    private fun refreshFromProvider() {
        val form = initialForm(formController.loadCurrentConfig())
        if (state.form != state.persistedForm) {
            state =
                ProxySettingsScreenState.from(
                    form = state.form,
                    persistedForm = form,
                )
            return
        }
        state =
            ProxySettingsScreenState.from(
                form = form,
                persistedForm = form,
            )
    }

    private fun initialForm(config: AppConfig): ProxySettingsFormState {
        val sensitiveConfigDisplay = formController.loadCurrentSensitiveConfigForDisplay()
        return ProxySettingsFormState.from(
            config = config,
            sensitiveConfig = sensitiveConfigDisplay.sensitiveConfig,
            sensitiveConfigInvalid = sensitiveConfigDisplay.sensitiveConfigInvalid,
        )
    }

    private fun saveChanges() {
        if (state.form == state.persistedForm) {
            return
        }
        when (val result = formController.save(state.form)) {
            is ProxySettingsSaveResult.Invalid -> {
                state =
                    ProxySettingsScreenState.from(
                        form = state.form,
                        persistedForm = state.persistedForm,
                        extraValidationErrors = result.validationErrors(),
                    )
                recordAuditSaveInvalid(result)?.let(pendingEffects::add)
                pendingEffects.add(ProxySettingsScreenEffect.SaveInvalid(result))
            }
            is ProxySettingsSaveResult.Saved -> {
                val nextForm =
                    state.form.afterSuccessfulSave(
                        savedConfig = result.config,
                        savedSensitiveConfig = result.sensitiveConfig,
                    )
                state =
                    ProxySettingsScreenState.from(
                        form = nextForm,
                        persistedForm = nextForm,
                    )
                recordAuditSaveSucceeded(result)?.let(pendingEffects::add)
                pendingEffects.add(ProxySettingsScreenEffect.SaveSucceeded(result))
            }
        }
    }

    private fun recordAuditSaveSucceeded(
        result: ProxySettingsSaveResult.Saved,
    ): ProxySettingsScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        ProxySettingsScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.Audit,
                severity = LogsAuditRecordSeverity.Info,
                title = "Settings save_settings",
                detail = "action=save_settings result=saved warningCount=${result.warnings.size}",
            ),
        )
    } else {
        null
    }

    private fun recordAuditSaveInvalid(
        result: ProxySettingsSaveResult.Invalid,
    ): ProxySettingsScreenEffect.RecordAuditAction? = if (auditActionsEnabled) {
        ProxySettingsScreenEffect.RecordAuditAction(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = auditOccurredAtEpochMillisProvider(),
                category = LogsAuditRecordCategory.Audit,
                severity = LogsAuditRecordSeverity.Warning,
                title = "Settings save_settings",
                detail = "action=save_settings result=invalid validationErrorCount=${result.validationErrors().size}",
            ),
        )
    } else {
        null
    }
}

private fun ProxySettingsSaveResult.Invalid.validationErrors(): Set<ProxySettingsValidationError> = buildSet {
    errors.forEach { error ->
        when (error) {
            ConfigValidationError.InvalidListenHost -> add(ProxySettingsValidationError.InvalidListenHost)
            ConfigValidationError.InvalidListenPort -> add(ProxySettingsValidationError.InvalidListenPort)
            ConfigValidationError.InvalidMaxConcurrentConnections ->
                add(ProxySettingsValidationError.InvalidMaxConcurrentConnections)
            ConfigValidationError.MissingCloudflareTunnelToken -> add(ProxySettingsValidationError.InvalidCloudflareTunnelToken)
        }
    }
    if (invalidProxyCredential) {
        add(ProxySettingsValidationError.InvalidProxyCredential)
    }
    if (invalidManagementApiToken) {
        add(ProxySettingsValidationError.InvalidManagementApiToken)
    }
    if (invalidCloudflareTunnelToken) {
        add(ProxySettingsValidationError.InvalidCloudflareTunnelToken)
    }
    if (invalidMaxConcurrentConnections) {
        add(ProxySettingsValidationError.InvalidMaxConcurrentConnections)
    }
    if (invalidRotationTiming) {
        add(ProxySettingsValidationError.InvalidRotationTiming)
    }
    if (invalidSensitiveConfiguration) {
        add(ProxySettingsValidationError.InvalidSensitiveConfiguration)
    }
}

sealed interface ProxySettingsScreenEvent {
    data class UpdateForm(
        val form: ProxySettingsFormState,
    ) : ProxySettingsScreenEvent

    data object SaveChanges : ProxySettingsScreenEvent

    data object Refresh : ProxySettingsScreenEvent

    data object DiscardChanges : ProxySettingsScreenEvent
}

sealed interface ProxySettingsScreenEffect {
    data class SaveSucceeded(
        val result: ProxySettingsSaveResult.Saved,
    ) : ProxySettingsScreenEffect

    data class SaveInvalid(
        val result: ProxySettingsSaveResult.Invalid,
    ) : ProxySettingsScreenEffect

    data class RecordAuditAction(
        val record: PersistedLogsAuditRecord,
    ) : ProxySettingsScreenEffect
}

sealed interface ProxySettingsSaveResult {
    data class Saved(
        val config: AppConfig,
        val warnings: Set<ProxySettingsFormWarning>,
        val sensitiveConfig: SensitiveConfig? = null,
    ) : ProxySettingsSaveResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
        val invalidProxyCredential: Boolean = false,
        val invalidManagementApiToken: Boolean = false,
        val invalidCloudflareTunnelToken: Boolean = false,
        val invalidMaxConcurrentConnections: Boolean = false,
        val invalidRotationTiming: Boolean = false,
        val invalidSensitiveConfiguration: Boolean = false,
    ) : ProxySettingsSaveResult
}

private sealed interface SensitiveConfigEditResult {
    data class Valid(
        val value: SensitiveConfig?,
    ) : SensitiveConfigEditResult

    data object InvalidProxyCredential : SensitiveConfigEditResult

    data object InvalidManagementApiToken : SensitiveConfigEditResult

    data object InvalidCloudflareTunnelToken : SensitiveConfigEditResult
}

private fun SensitiveConfig.withProxyCredentialEdit(
    username: String,
    password: String,
): SensitiveConfigEditResult {
    if (username.isEmpty() && password.isEmpty()) {
        return SensitiveConfigEditResult.Valid(this)
    }
    if (username.isEmpty() || password.isEmpty()) {
        return SensitiveConfigEditResult.InvalidProxyCredential
    }

    val credential =
        runCatching {
            ProxyCredential(
                username = username,
                password = password,
            )
        }.getOrNull() ?: return SensitiveConfigEditResult.InvalidProxyCredential

    return SensitiveConfigEditResult.Valid(
        copy(proxyCredential = credential),
    )
}

private fun SensitiveConfigEditResult.withManagementApiTokenEdit(managementApiToken: String): SensitiveConfigEditResult = when (this) {
    SensitiveConfigEditResult.InvalidProxyCredential,
    SensitiveConfigEditResult.InvalidManagementApiToken,
    SensitiveConfigEditResult.InvalidCloudflareTunnelToken,
    -> this
    is SensitiveConfigEditResult.Valid -> {
        if (managementApiToken.isEmpty()) {
            this
        } else if (managementApiToken.isBlank() || managementApiToken != managementApiToken.trim()) {
            SensitiveConfigEditResult.InvalidManagementApiToken
        } else {
            SensitiveConfigEditResult.Valid(value?.copy(managementApiToken = managementApiToken))
        }
    }
}

private fun SensitiveConfigEditResult.withCloudflareTunnelTokenEdit(cloudflareTunnelToken: String): SensitiveConfigEditResult = when (this) {
    SensitiveConfigEditResult.InvalidProxyCredential,
    SensitiveConfigEditResult.InvalidManagementApiToken,
    SensitiveConfigEditResult.InvalidCloudflareTunnelToken,
    -> this
    is SensitiveConfigEditResult.Valid -> {
        if (cloudflareTunnelToken.isEmpty()) {
            this
        } else if (cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit()) {
            SensitiveConfigEditResult.InvalidCloudflareTunnelToken
        } else {
            SensitiveConfigEditResult.Valid(value?.copy(cloudflareTunnelToken = cloudflareTunnelToken))
        }
    }
}

private fun ProxySettingsFormState.hasInvalidProxyCredentialEdit(): Boolean {
    if (proxyUsername.isEmpty() && proxyPassword.isEmpty()) {
        return false
    }
    if (proxyUsername.isEmpty() || proxyPassword.isEmpty()) {
        return true
    }

    return runCatching {
        ProxyCredential(
            username = proxyUsername,
            password = proxyPassword,
        )
    }.isFailure
}

private fun ProxySettingsFormState.hasInvalidManagementApiTokenEdit(): Boolean = managementApiToken.isNotEmpty() &&
    (managementApiToken.isBlank() || managementApiToken != managementApiToken.trim())

private fun ProxySettingsFormState.hasInvalidCloudflareTunnelTokenEdit(): Boolean = cloudflareTunnelToken.isNotEmpty() &&
    cloudflareTunnelToken.isInvalidCloudflareTunnelTokenEdit()

private fun ProxySettingsFormState.replacementSensitiveConfigOrNull(): SensitiveConfig? {
    if (proxyUsername.isEmpty() || proxyPassword.isEmpty() || managementApiToken.isEmpty()) {
        return null
    }
    if (hasInvalidProxyCredentialEdit() || hasInvalidManagementApiTokenEdit() || hasInvalidCloudflareTunnelTokenEdit()) {
        return null
    }

    return SensitiveConfig(
        proxyCredential =
            ProxyCredential(
                username = proxyUsername,
                password = proxyPassword,
            ),
        managementApiToken = managementApiToken,
        cloudflareTunnelToken = cloudflareTunnelToken.ifBlank { null },
    )
}

private fun ProxySettingsFormState.validationErrors(): Set<ProxySettingsValidationError> = buildSet {
    if (
        AppConfig
            .default()
            .copy(
                proxy =
                    ProxyConfig(
                        listenHost = listenHost.trim(),
                        listenPort = listenPort.trim().toStrictPortOrNull() ?: INVALID_PORT_SENTINEL,
                        authEnabled = authEnabled,
                        maxConcurrentConnections = 1,
                    ),
            ).validate()
            .errors
            .contains(ConfigValidationError.InvalidListenHost)
    ) {
        add(ProxySettingsValidationError.InvalidListenHost)
    }
    if (listenPort.trim().toStrictPortOrNull() == null) {
        add(ProxySettingsValidationError.InvalidListenPort)
    }
    if (maxConcurrentConnections.trim().toStrictPositiveIntOrNull() == null) {
        add(ProxySettingsValidationError.InvalidMaxConcurrentConnections)
    }
    if (
        mobileDataOffDelaySeconds.trim().toStrictPositiveSecondsDurationOrNull() == null ||
        networkReturnTimeoutSeconds.trim().toStrictPositiveSecondsDurationOrNull() == null ||
        cooldownSeconds.trim().toStrictPositiveSecondsDurationOrNull() == null
    ) {
        add(ProxySettingsValidationError.InvalidRotationTiming)
    }
    if (hasInvalidProxyCredentialEdit()) {
        add(ProxySettingsValidationError.InvalidProxyCredential)
    }
    if (hasInvalidManagementApiTokenEdit()) {
        add(ProxySettingsValidationError.InvalidManagementApiToken)
    }
    if (hasInvalidCloudflareTunnelTokenEdit()) {
        add(ProxySettingsValidationError.InvalidCloudflareTunnelToken)
    }
}

private fun String.isInvalidCloudflareTunnelTokenEdit(): Boolean = isBlank() ||
    this != trim() ||
    CloudflareTunnelToken.parse(this) !is CloudflareTunnelTokenParseResult.Valid

private fun String.toStrictPortOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it in TCP_PORT_RANGE }
}

private fun String.toStrictPositiveIntOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toIntOrNull()?.takeIf { it > 0 }
}

private fun String.toStrictPositiveSecondsDurationOrNull(): Duration? {
    if (isEmpty() || any { it !in '0'..'9' }) {
        return null
    }

    return toLongOrNull()
        ?.takeIf { it > 0 }
        ?.seconds
}

private val TCP_PORT_RANGE = 1..65_535
private const val INVALID_PORT_SENTINEL = 0
private const val INVALID_POSITIVE_INT_SENTINEL = 0
