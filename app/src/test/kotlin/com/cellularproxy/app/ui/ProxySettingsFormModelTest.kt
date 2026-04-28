package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxySettingsFormModelTest {
    @Test
    fun `form state starts from persisted proxy and route config`() {
        val config =
            AppConfig.default().copy(
                network =
                    AppConfig.default().network.copy(
                        defaultRoutePolicy = RouteTarget.Cellular,
                    ),
                cloudflare =
                    AppConfig.default().cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = true,
                        managementHostnameLabel = "manage.example.com",
                    ),
                rotation =
                    AppConfig.default().rotation.copy(
                        strictIpChangeRequired = true,
                        mobileDataOffDelay = 7.seconds,
                        networkReturnTimeout = 90.seconds,
                        cooldown = 300.seconds,
                    ),
            )

        val form = ProxySettingsFormState.from(config)

        assertEquals("0.0.0.0", form.listenHost)
        assertEquals("8080", form.listenPort)
        assertEquals(true, form.authEnabled)
        assertEquals("64", form.maxConcurrentConnections)
        assertEquals(RouteTarget.Cellular, form.route)
        assertEquals(true, form.cloudflareEnabled)
        assertEquals("manage.example.com", form.cloudflareHostnameLabel)
        assertEquals(true, form.strictIpChangeRequired)
        assertEquals("7", form.mobileDataOffDelaySeconds)
        assertEquals("90", form.networkReturnTimeoutSeconds)
        assertEquals("300", form.cooldownSeconds)
        assertEquals(false, form.rootOperationsEnabled)
    }

    @Test
    fun `settings actions are available only when editable fields changed`() {
        val persisted = ProxySettingsFormState.from(AppConfig.default())
        val unchangedState =
            ProxySettingsScreenState.from(
                form = persisted,
                persistedForm = persisted,
            )
        val changedPlainSettingState =
            ProxySettingsScreenState.from(
                form = persisted.copy(listenPort = "8181"),
                persistedForm = persisted,
            )
        val changedSensitiveSettingState =
            ProxySettingsScreenState.from(
                form = persisted.copy(managementApiToken = "new-management-token"),
                persistedForm = persisted,
            )

        assertEquals(
            emptyList(),
            unchangedState.availableActions,
        )
        assertEquals(
            listOf(
                ProxySettingsScreenAction.SaveChanges,
                ProxySettingsScreenAction.DiscardChanges,
            ),
            changedPlainSettingState.availableActions,
        )
        assertEquals(
            listOf(
                ProxySettingsScreenAction.SaveChanges,
                ProxySettingsScreenAction.DiscardChanges,
            ),
            changedSensitiveSettingState.availableActions,
        )
    }

    @Test
    fun `settings screen state exposes safe Cloudflare tunnel token status`() {
        val missingForm = ProxySettingsFormState.from(AppConfig.default())
        val presentForm =
            ProxySettingsFormState.from(
                AppConfig.default().copy(
                    cloudflare =
                        AppConfig.default().cloudflare.copy(
                            tunnelTokenPresent = true,
                        ),
                ),
            )
        val editedForm =
            missingForm.copy(
                cloudflareTunnelToken = "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0=",
            )
        val invalidForm =
            missingForm.copy(
                cloudflareTunnelToken = "not-a-tunnel-token",
            )

        assertEquals(
            ProxySettingsCloudflareTokenStatus.Missing,
            ProxySettingsScreenState
                .from(form = missingForm, persistedForm = missingForm)
                .cloudflareTokenStatus,
        )
        assertEquals(
            ProxySettingsCloudflareTokenStatus.Present,
            ProxySettingsScreenState
                .from(form = presentForm, persistedForm = presentForm)
                .cloudflareTokenStatus,
        )
        assertEquals(
            ProxySettingsCloudflareTokenStatus.Edited,
            ProxySettingsScreenState
                .from(form = editedForm, persistedForm = missingForm)
                .cloudflareTokenStatus,
        )
        assertEquals(
            ProxySettingsCloudflareTokenStatus.Invalid,
            ProxySettingsScreenState
                .from(form = invalidForm, persistedForm = missingForm)
                .cloudflareTokenStatus,
        )
    }

    @Test
    fun `settings screen state exposes visible warnings for risky current form`() {
        val persisted = ProxySettingsFormState.from(AppConfig.default())
        val cloudflareMissingTokenForm =
            persisted.copy(
                cloudflareEnabled = true,
                cloudflareTunnelTokenPresent = false,
            )
        val broadUnauthenticatedForm =
            persisted.copy(
                listenHost = "0.0.0.0",
                authEnabled = false,
            )
        val cloudflareInvalidTokenForm =
            persisted.copy(
                cloudflareEnabled = true,
                cloudflareTunnelToken = "not-a-tunnel-token",
            )

        assertEquals(
            setOf(ProxySettingsFormWarning.CloudflareEnabledMissingTunnelToken),
            ProxySettingsScreenState
                .from(form = cloudflareMissingTokenForm, persistedForm = persisted)
                .warnings,
        )
        assertEquals(
            setOf(ProxySettingsFormWarning.BroadUnauthenticatedProxy),
            ProxySettingsScreenState
                .from(form = broadUnauthenticatedForm, persistedForm = persisted)
                .warnings,
        )
        assertEquals(
            setOf(ProxySettingsFormWarning.CloudflareEnabledInvalidTunnelToken),
            ProxySettingsScreenState
                .from(form = cloudflareInvalidTokenForm, persistedForm = persisted)
                .warnings,
        )
    }

    @Test
    fun `settings controller refreshes clean form from latest persisted config provider`() {
        var config = AppConfig.default()
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = { config },
                formController =
                    ProxySettingsFormController(
                        loadConfig = { config },
                        saveConfig = { savedConfig -> config = savedConfig },
                    ),
            )
        config =
            AppConfig.default().copy(
                proxy =
                    AppConfig.default().proxy.copy(
                        listenPort = 8181,
                    ),
            )

        controller.handle(ProxySettingsScreenEvent.Refresh)

        assertEquals("8181", controller.state.form.listenPort)
        assertEquals("8181", controller.state.persistedForm.listenPort)
        assertEquals(emptyList(), controller.state.availableActions)
    }

    @Test
    fun `settings controller refresh preserves dirty edits while updating persisted baseline`() {
        var config = AppConfig.default()
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = { config },
                formController =
                    ProxySettingsFormController(
                        loadConfig = { config },
                        saveConfig = { savedConfig -> config = savedConfig },
                    ),
            )
        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(listenHost = "127.0.0.1"),
            ),
        )
        config =
            AppConfig.default().copy(
                proxy =
                    AppConfig.default().proxy.copy(
                        listenPort = 8181,
                    ),
            )

        controller.handle(ProxySettingsScreenEvent.Refresh)

        assertEquals("127.0.0.1", controller.state.form.listenHost)
        assertEquals("8080", controller.state.form.listenPort)
        assertEquals("8181", controller.state.persistedForm.listenPort)

        controller.handle(ProxySettingsScreenEvent.DiscardChanges)

        assertEquals("8181", controller.state.form.listenPort)
        assertEquals("8181", controller.state.persistedForm.listenPort)
        assertEquals(emptyList(), controller.state.availableActions)
    }

    @Test
    fun `settings controller exposes invalid sensitive storage after failed sensitive save`() {
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = AppConfig::default,
                formController =
                    ProxySettingsFormController(
                        loadConfig = AppConfig::default,
                        saveConfig = {},
                        loadSensitiveConfigResult = {
                            SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret)
                        },
                        saveSensitiveConfig = {},
                    ),
            )
        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(managementApiToken = "new-management-token"),
            ),
        )

        controller.handle(ProxySettingsScreenEvent.SaveChanges)

        assertEquals(
            setOf(ProxySettingsValidationError.InvalidSensitiveConfiguration),
            controller.state.validationErrors,
        )
        assertTrue(
            controller.consumeEffects().single() is ProxySettingsScreenEffect.SaveInvalid,
        )
    }

    @Test
    fun `settings controller exposes Cloudflare token validation after rejected save`() {
        var savedConfig: AppConfig? = null
        val controller =
            ProxySettingsScreenController(
                initialConfigProvider = AppConfig::default,
                formController =
                    ProxySettingsFormController(
                        loadConfig = AppConfig::default,
                        saveConfig = { config -> savedConfig = config },
                        loadSensitiveConfigResult = { SensitiveConfigLoadResult.Loaded(sensitiveConfig("management-token")) },
                        saveSensitiveConfig = {},
                    ),
            )
        controller.handle(
            ProxySettingsScreenEvent.UpdateForm(
                controller.state.form.copy(cloudflareEnabled = true),
            ),
        )

        controller.handle(ProxySettingsScreenEvent.SaveChanges)

        assertEquals(null, savedConfig)
        assertEquals(
            setOf(ProxySettingsValidationError.InvalidCloudflareTunnelToken),
            controller.state.validationErrors,
        )
        assertTrue(
            controller.consumeEffects().single() is ProxySettingsScreenEffect.SaveInvalid,
        )
    }

    @Test
    fun `settings form controller saves through latest sensitive callback provider`() {
        val oldSavedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        val newSavedSensitiveConfigs = mutableListOf<SensitiveConfig>()
        var loadSensitiveConfig = { sensitiveConfig("old-management-token") }
        var saveSensitiveConfig: (SensitiveConfig) -> Unit = oldSavedSensitiveConfigs::add
        val controller =
            ProxySettingsFormController(
                loadConfig = AppConfig::default,
                saveConfig = {},
                loadSensitiveConfigProvider = { loadSensitiveConfig },
                saveSensitiveConfigProvider = { saveSensitiveConfig },
            )
        loadSensitiveConfig = { sensitiveConfig("new-management-token") }
        saveSensitiveConfig = newSavedSensitiveConfigs::add

        controller.save(
            ProxySettingsFormState
                .from(AppConfig.default())
                .copy(managementApiToken = "newer-management-token"),
        )

        assertEquals(emptyList(), oldSavedSensitiveConfigs)
        assertEquals(listOf("newer-management-token"), newSavedSensitiveConfigs.map(SensitiveConfig::managementApiToken))
    }

    @Test
    fun `settings state withholds save and exposes validation errors for invalid dirty fields`() {
        val persisted = ProxySettingsFormState.from(AppConfig.default())
        val state =
            ProxySettingsScreenState.from(
                form =
                    persisted.copy(
                        listenPort = "65536",
                        maxConcurrentConnections = "0",
                        mobileDataOffDelaySeconds = "1.5",
                        proxyUsername = "new-user",
                    ),
                persistedForm = persisted,
            )

        assertEquals(
            listOf(ProxySettingsScreenAction.DiscardChanges),
            state.availableActions,
        )
        assertEquals(
            setOf(
                ProxySettingsValidationError.InvalidListenPort,
                ProxySettingsValidationError.InvalidMaxConcurrentConnections,
                ProxySettingsValidationError.InvalidRotationTiming,
                ProxySettingsValidationError.InvalidProxyCredential,
            ),
            state.validationErrors,
        )
    }

    @Test
    fun `successful settings save clears sensitive edit fields from the next editable baseline`() {
        val savedConfig =
            AppConfig.default().copy(
                proxy =
                    AppConfig.default().proxy.copy(
                        listenPort = 8181,
                    ),
                cloudflare =
                    AppConfig.default().cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = true,
                        managementHostnameLabel = "manage.example.com",
                    ),
            )
        val editedForm =
            ProxySettingsFormState
                .from(AppConfig.default())
                .copy(
                    listenPort = "8181",
                    proxyUsername = "new-user",
                    proxyPassword = "new-password",
                    managementApiToken = "new-management-token",
                    cloudflareEnabled = true,
                    cloudflareTunnelToken = "eyJhIjoiZmFrZSIsInQiOiJmYWtlIiwicyI6Ik1EUXpNVEkyTnpndE9Ua3dPUzAwTkRnNUxUaGhOV1F0TWpVMk1qYzRPVEV5TXpRMSJ9",
                    cloudflareHostnameLabel = "manage.example.com",
                )

        val nextForm = editedForm.afterSuccessfulSave(savedConfig)

        assertEquals("8181", nextForm.listenPort)
        assertEquals(true, nextForm.cloudflareEnabled)
        assertEquals("manage.example.com", nextForm.cloudflareHostnameLabel)
        assertEquals("", nextForm.proxyUsername)
        assertEquals("", nextForm.proxyPassword)
        assertEquals("", nextForm.managementApiToken)
        assertEquals("", nextForm.cloudflareTunnelToken)
    }

    @Test
    fun `successful settings save preserves edited Cloudflare fields when saved config has stale Cloudflare state`() {
        val savedConfigWithStaleCloudflare = AppConfig.default()
        val editedForm =
            ProxySettingsFormState
                .from(AppConfig.default())
                .copy(
                    cloudflareEnabled = true,
                    cloudflareHostnameLabel = "manage.example.com",
                    cloudflareTunnelToken = "eyJhIjoiZmFrZSIsInQiOiJmYWtlIiwicyI6Ik1EUXpNVEkyTnpndE9Ua3dPUzAwTkRnNUxUaGhOV1F0TWpVMk1qYzRPVEV5TXpRMSJ9",
                )

        val nextForm = editedForm.afterSuccessfulSave(savedConfigWithStaleCloudflare)

        assertEquals(true, nextForm.cloudflareEnabled)
        assertEquals("manage.example.com", nextForm.cloudflareHostnameLabel)
        assertEquals("", nextForm.cloudflareTunnelToken)
    }

    @Test
    fun `valid settings produce updated config and broad unauthenticated warning`() {
        val result =
            ProxySettingsFormState(
                listenHost = " 0.0.0.0 ",
                listenPort = " 8181 ",
                authEnabled = false,
                maxConcurrentConnections = "12",
                route = RouteTarget.Vpn,
            ).toAppConfig(base = AppConfig.default())

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals("0.0.0.0", saved.config.proxy.listenHost)
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(false, saved.config.proxy.authEnabled)
        assertEquals(12, saved.config.proxy.maxConcurrentConnections)
        assertEquals(RouteTarget.Vpn, saved.config.network.defaultRoutePolicy)
        assertEquals(setOf(ProxySettingsFormWarning.BroadUnauthenticatedProxy), saved.warnings)
    }

    @Test
    fun `invalid host and port return validation errors without throwing`() {
        val result =
            ProxySettingsFormState(
                listenHost = "not-an-ip",
                listenPort = "+8080",
                authEnabled = true,
                route = RouteTarget.Automatic,
            ).toAppConfig(base = AppConfig.default())

        val invalid = result as ProxySettingsFormResult.Invalid
        assertEquals(
            listOf(ConfigValidationError.InvalidListenHost, ConfigValidationError.InvalidListenPort),
            invalid.errors,
        )
    }

    @Test
    fun `blank and out of range ports are rejected`() {
        listOf("", "0", "65536").forEach { port ->
            val result =
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = port,
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid &&
                    ConfigValidationError.InvalidListenPort in result.errors,
                "Port $port should be invalid",
            )
        }
    }

    @Test
    fun `blank zero negative and fractional maximum concurrent connections are rejected`() {
        val invalidValues = listOf("", "0", "-1", "1.5")

        invalidValues.forEach { invalidValue ->
            val result =
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8181",
                    authEnabled = true,
                    maxConcurrentConnections = invalidValue,
                    route = RouteTarget.Automatic,
                ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid && result.invalidMaxConcurrentConnections,
                "maxConcurrentConnections=$invalidValue should be invalid",
            )
        }
    }

    @Test
    fun `proxy settings save ignores unrelated stale Cloudflare token presence validation`() {
        val staleCloudflareConfig =
            AppConfig.default().copy(
                cloudflare =
                    AppConfig.default().cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = false,
                    ),
            )

        val result =
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8181",
                authEnabled = true,
                route = RouteTarget.Automatic,
            ).toAppConfig(base = staleCloudflareConfig)

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals("127.0.0.1", saved.config.proxy.listenHost)
        assertEquals(8181, saved.config.proxy.listenPort)
        assertEquals(staleCloudflareConfig.cloudflare, saved.config.cloudflare)
    }

    @Test
    fun `valid settings update rotation controls`() {
        val base =
            AppConfig.default().copy(
                rotation =
                    RotationConfig(
                        strictIpChangeRequired = false,
                        mobileDataOffDelay = 7.seconds,
                        networkReturnTimeout = 90.seconds,
                        cooldown = 300.seconds,
                    ),
            )

        val result =
            ProxySettingsFormState(
                listenHost = "127.0.0.1",
                listenPort = "8181",
                authEnabled = true,
                route = RouteTarget.Automatic,
                strictIpChangeRequired = true,
                mobileDataOffDelaySeconds = " 11 ",
                networkReturnTimeoutSeconds = "120",
                cooldownSeconds = "360",
                rootOperationsEnabled = true,
            ).toAppConfig(base = base)

        val saved = result as ProxySettingsFormResult.Valid
        assertEquals(true, saved.config.rotation.strictIpChangeRequired)
        assertEquals(11.seconds, saved.config.rotation.mobileDataOffDelay)
        assertEquals(120.seconds, saved.config.rotation.networkReturnTimeout)
        assertEquals(360.seconds, saved.config.rotation.cooldown)
        assertEquals(true, saved.config.root.operationsEnabled)
    }

    @Test
    fun `blank zero negative and fractional rotation timing fields are rejected`() {
        val invalidValues = listOf("", "0", "-1", "1.5")

        invalidValues.forEach { invalidValue ->
            val result =
                ProxySettingsFormState(
                    listenHost = "127.0.0.1",
                    listenPort = "8181",
                    authEnabled = true,
                    route = RouteTarget.Automatic,
                    mobileDataOffDelaySeconds = invalidValue,
                ).toAppConfig(base = AppConfig.default())

            assertTrue(
                result is ProxySettingsFormResult.Invalid && result.invalidRotationTiming,
                "mobileDataOffDelaySeconds=$invalidValue should be invalid",
            )
        }
    }

    private fun sensitiveConfig(managementApiToken: String): SensitiveConfig = SensitiveConfig(
        proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
        managementApiToken = managementApiToken,
    )
}
