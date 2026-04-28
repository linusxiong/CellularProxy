package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxySettingsFormControllerTest {
    @Test
    fun `screen controller emits metadata only audit effects for settings save attempts`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = AppConfig::default,
                formController =
                    ProxySettingsFormController(
                        loadConfig = AppConfig::default,
                        saveConfig = savedConfigs::add,
                        loadSensitiveConfig = {
                            SensitiveConfig(
                                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                                managementApiToken = "old-management-token",
                            )
                        },
                        saveSensitiveConfig = savedSensitiveConfigs::add,
                    ),
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 1234L },
            )

        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(managementApiToken = "new-management-token"),
            ),
        )
        controller.handle(ProxySettingsScreenEvent.SaveChanges)
        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(cloudflareEnabled = true),
            ),
        )
        controller.handle(ProxySettingsScreenEvent.SaveChanges)

        val auditRecords =
            controller
                .consumeEffects()
                .filterIsInstance<ProxySettingsScreenEffect.RecordAuditAction>()
                .map(ProxySettingsScreenEffect.RecordAuditAction::record)
        assertEquals(
            listOf(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 1234L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Settings save_settings",
                    detail = "action=save_settings result=saved warningCount=0",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 1234L,
                    category = LogsAuditRecordCategory.Audit,
                    severity = LogsAuditRecordSeverity.Warning,
                    title = "Settings save_settings",
                    detail = "action=save_settings result=invalid validationErrorCount=1",
                ),
            ),
            auditRecords,
        )
        assertFalse(auditRecords.joinToString(separator = "\n").contains("new-management-token"))
        assertFalse(auditRecords.joinToString(separator = "\n").contains("old-management-token"))
    }

    @Test
    fun `screen controller edits saves discards and emits one-shot save effects`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val formController =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = AppConfig::default,
                formController = formController,
            )

        val editedForm =
            controller.state.form.copy(
                listenPort = "9999",
                authEnabled = false,
                managementApiToken = "typed-secret",
            )
        controller.handle(ProxySettingsScreenEvent.UpdateForm(editedForm))

        assertEquals(editedForm, controller.state.form)
        assertTrue(ProxySettingsScreenAction.SaveChanges in controller.state.availableActions)

        controller.handle(ProxySettingsScreenEvent.SaveChanges)

        val savedEffect = controller.consumeEffects().single() as ProxySettingsScreenEffect.SaveSucceeded
        assertEquals("9999", controller.state.form.listenPort)
        assertEquals("", controller.state.form.managementApiToken)
        assertEquals(controller.state.persistedForm, controller.state.form)
        assertEquals(savedConfigs.single(), savedEffect.result.config)
        assertEquals(setOf(ProxySettingsFormWarning.BroadUnauthenticatedProxy), savedEffect.result.warnings)
        assertTrue(controller.consumeEffects().isEmpty())

        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(cloudflareEnabled = true),
            ),
        )
        controller.handle(ProxySettingsScreenEvent.SaveChanges)

        val invalidEffect = controller.consumeEffects().single() as ProxySettingsScreenEffect.SaveInvalid
        assertTrue(invalidEffect.result.invalidCloudflareTunnelToken)
        assertEquals(true, controller.state.form.cloudflareEnabled)
        assertEquals(listOf(savedEffect.result.config), savedConfigs)
        assertEquals(listOf(savedEffect.result.sensitiveConfig), savedSensitiveConfigs)

        controller.handle(ProxySettingsScreenEvent.DiscardChanges)

        assertEquals(controller.state.persistedForm, controller.state.form)
    }

    @Test
    fun `valid form saves updated proxy settings while preserving unrelated config`() {
        val original =
            AppConfig.default().copy(
                cloudflare =
                    AppConfig.default().cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = true,
                        managementHostnameLabel = "manage.example.com",
                    ),
                rotation =
                    AppConfig.default().rotation.copy(
                        mobileDataOffDelay = 5.seconds,
                        networkReturnTimeout = 70.seconds,
                        cooldown = 200.seconds,
                    ),
            )
        val savedConfigs = mutableListOf<AppConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = { original },
                saveConfig = savedConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = false,
                    maxConcurrentConnections = "9",
                    route = RouteTarget.WiFi,
                    strictIpChangeRequired = true,
                    mobileDataOffDelaySeconds = "8",
                    networkReturnTimeoutSeconds = "95",
                    cooldownSeconds = "240",
                    rootOperationsEnabled = true,
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals("127.0.0.1", saved.config.proxy.listenHost)
        assertEquals(8888, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
        assertEquals(9, saved.config.proxy.maxConcurrentConnections)
        assertEquals(RouteTarget.WiFi, saved.config.network.defaultRoutePolicy)
        assertEquals(true, saved.config.rotation.strictIpChangeRequired)
        assertEquals(8.seconds, saved.config.rotation.mobileDataOffDelay)
        assertEquals(95.seconds, saved.config.rotation.networkReturnTimeout)
        assertEquals(240.seconds, saved.config.rotation.cooldown)
        assertEquals(true, saved.config.root.operationsEnabled)
        assertEquals(original.cloudflare, saved.config.cloudflare)
        assertEquals(listOf(saved.config), savedConfigs)
    }

    @Test
    fun `valid plain settings save does not load sensitive config`() {
        var loadSensitiveConfigCalled = false
        val savedConfigs = mutableListOf<AppConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    error("plain settings save must not require sensitive config")
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState
                    .from(AppConfig.default())
                    .copy(listenPort = "8181"),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(listOf(saved.config), savedConfigs)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `sensitive edit save reports invalid sensitive storage without saving plain config`() {
        listOf(
            SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            SensitiveConfigLoadResult.MissingRequiredSecrets,
        ).forEach { loadResult ->
            assertSensitiveEditSaveReportsInvalidSensitiveStorage(loadResult)
        }
    }

    private fun assertSensitiveEditSaveReportsInvalidSensitiveStorage(loadResult: SensitiveConfigLoadResult) {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfigResult = { loadResult },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState
                    .from(AppConfig.default())
                    .copy(managementApiToken = "new-management-token"),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidSensitiveConfiguration)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `invalid rotation timing is rejected before sensitive config is loaded`() {
        val savedConfigs = mutableListOf<AppConfig>()
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    networkReturnTimeoutSeconds = "0",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidRotationTiming)
        assertTrue(savedConfigs.isEmpty())
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `invalid maximum concurrent connections is rejected before sensitive config is loaded`() {
        val savedConfigs = mutableListOf<AppConfig>()
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    maxConcurrentConnections = "0",
                    route = RouteTarget.Automatic,
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidMaxConcurrentConnections)
        assertTrue(savedConfigs.isEmpty())
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `invalid form returns errors and does not save`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "bad host",
                    listenPort = "8080",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertEquals(listOf(ConfigValidationError.InvalidListenHost), invalid.errors)
        assertTrue(savedConfigs.isEmpty())
    }

    @Test
    fun `credential fields update encrypted proxy credential while preserving other sensitive values`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
                cloudflareTunnelToken = "cloudflare-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Cellular,
                    proxyUsername = "new-user",
                    proxyPassword = "new-pass",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(ProxyCredential(username = "new-user", password = "new-pass"), saved.sensitiveConfig?.proxyCredential)
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals("cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(listOf(saved.config), savedConfigs)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `credential edit preserves exact password text including surrounding spaces`() {
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    proxyUsername = "new-user",
                    proxyPassword = " new-pass ",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(" new-pass ", saved.sensitiveConfig?.proxyCredential?.password)
    }

    @Test
    fun `management token field updates encrypted management token while preserving other sensitive values`() {
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "old-management-token",
                cloudflareTunnelToken = "cloudflare-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    managementApiToken = "new-management-token",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals("new-management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals("cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `cloudflare fields enable tunnel with encrypted token and display hostname label`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val cloudflareToken = validCloudflareTunnelToken()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareEnabled = true,
                    cloudflareTunnelToken = cloudflareToken,
                    cloudflareHostnameLabel = " manage.example.com ",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(true, saved.config.cloudflare.enabled)
        assertEquals(true, saved.config.cloudflare.tunnelTokenPresent)
        assertEquals("manage.example.com", saved.config.cloudflare.managementHostnameLabel)
        assertEquals(cloudflareToken, saved.sensitiveConfig?.cloudflareTunnelToken)
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals(listOf(saved.config), savedConfigs)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `cloudflare hostname label save strips unsafe url details before persistence`() {
        val cloudflareToken = validCloudflareTunnelToken()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareEnabled = true,
                    cloudflareTunnelToken = cloudflareToken,
                    cloudflareHostnameLabel =
                        " https://operator:hostname-secret@manage.example.com/private?token=query-secret#fragment ",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals("https://manage.example.com", saved.config.cloudflare.managementHostnameLabel)
    }

    @Test
    fun `blank cloudflare token field preserves existing token while allowing hostname removal`() {
        val originalConfig =
            AppConfig.default().copy(
                cloudflare =
                    AppConfig.default().cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = true,
                        managementHostnameLabel = "old.example.com",
                    ),
            )
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
                cloudflareTunnelToken = "existing-cloudflare-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = { originalConfig },
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareEnabled = true,
                    cloudflareHostnameLabel = "   ",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(true, saved.config.cloudflare.enabled)
        assertEquals(true, saved.config.cloudflare.tunnelTokenPresent)
        assertEquals(null, saved.config.cloudflare.managementHostnameLabel)
        assertEquals("existing-cloudflare-token", saved.sensitiveConfig?.cloudflareTunnelToken)
    }

    @Test
    fun `enabling cloudflare without existing or edited tunnel token is rejected without saving`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareEnabled = true,
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidCloudflareTunnelToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `blank after trim cloudflare tunnel token edit is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareTunnelToken = "   ",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidCloudflareTunnelToken)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `malformed cloudflare tunnel token edit is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    cloudflareTunnelToken = "not-base64",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidCloudflareTunnelToken)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `management token edit with surrounding whitespace is rejected without saving`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "old-management-token",
            )
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    managementApiToken = " new-management-token ",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `blank management token field preserves current sensitive management token`() {
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    proxyUsername = "new-user",
                    proxyPassword = "new-pass",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals("management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals(ProxyCredential(username = "new-user", password = "new-pass"), saved.sensitiveConfig?.proxyCredential)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `blank after trim management token edit is rejected without saving plain or sensitive state`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    managementApiToken = "   ",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `blank credential fields preserve current sensitive proxy credential`() {
        val originalSensitiveConfig =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                managementApiToken = "management-token",
            )
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = { originalSensitiveConfig },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    managementApiToken = "new-management-token",
                ),
            )

        val saved = result as ProxySettingsSaveResult.Saved
        assertEquals(originalSensitiveConfig.proxyCredential, saved.sensitiveConfig?.proxyCredential)
        assertEquals("new-management-token", saved.sensitiveConfig?.managementApiToken)
        assertEquals(listOf(saved.sensitiveConfig), savedSensitiveConfigs)
    }

    @Test
    fun `partial credential edit is rejected without saving plain or sensitive state`() {
        val savedConfigs = mutableListOf<AppConfig>()
        val savedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = savedConfigs::add,
                loadSensitiveConfig = {
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = savedSensitiveConfigs::add,
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    proxyUsername = "new-user",
                    proxyPassword = "",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidProxyCredential)
        assertTrue(savedConfigs.isEmpty())
        assertTrue(savedSensitiveConfigs.isEmpty())
    }

    @Test
    fun `invalid plain form is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "bad host",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                ),
            )

        assertTrue(result is ProxySettingsSaveResult.Invalid)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `partial credential edit is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    proxyUsername = "new-user",
                    proxyPassword = "",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidProxyCredential)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    @Test
    fun `blank after trim management token edit is rejected before sensitive config is loaded`() {
        var loadSensitiveConfigCalled = false
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfig = {
                    loadSensitiveConfigCalled = true
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "old-user", password = "old-pass"),
                        managementApiToken = "management-token",
                    )
                },
                saveSensitiveConfig = {},
            )

        val result =
            controller.save(
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8888",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    managementApiToken = "   ",
                ),
            )

        val invalid = result as ProxySettingsSaveResult.Invalid
        assertTrue(invalid.invalidManagementApiToken)
        assertEquals(false, loadSensitiveConfigCalled)
    }

    private fun validCloudflareTunnelToken(): String {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
        val json = """{"a":"account-tag","s":"$secret","t":"$tunnelId","e":"edge.example.com"}"""
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
}
