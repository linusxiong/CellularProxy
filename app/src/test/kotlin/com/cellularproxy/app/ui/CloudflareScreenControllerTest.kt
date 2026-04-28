package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareScreenControllerTest {
    @Test
    fun `controller dispatches available lifecycle actions and refreshes screen state`() {
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val actions = mutableListOf<CloudflareScreenAction>()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action ->
                    actions += action
                    tunnelStatus =
                        when (action) {
                            CloudflareScreenAction.StartTunnel -> CloudflareTunnelStatus.starting()
                            else -> tunnelStatus
                        }
                },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)
        assertEquals("Starting", controller.state.lifecycleState)
        assertTrue(CloudflareScreenAction.StopTunnel in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses unavailable lifecycle actions`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = false) },
                tunnelStatusProvider = { CloudflareTunnelStatus.stopped() },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertTrue(actions.isEmpty())
        assertEquals(listOf(CloudflareScreenAction.CopyDiagnostics), controller.state.availableActions)
    }

    @Test
    fun `controller suppresses duplicate operations until provider state changes`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)
        assertFalse(CloudflareScreenAction.StartTunnel in controller.state.availableActions)

        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(listOf(CloudflareScreenAction.StartTunnel), actions)

        tunnelStatus = CloudflareTunnelStatus.starting()
        controller.handle(CloudflareScreenEvent.Refresh)
        tunnelStatus = CloudflareTunnelStatus.failed("edge connection failed")
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.StartTunnel,
                CloudflareScreenAction.StartTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `controller allows another management tunnel test after round trip result changes`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var managementRoundTrip: String? = null
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true, managementHostname = "management.example.test") },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { managementRoundTrip },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(listOf(CloudflareScreenAction.TestManagementTunnel), actions)
        assertFalse(CloudflareScreenAction.TestManagementTunnel in controller.state.availableActions)

        managementRoundTrip = "HTTP 200 OK"
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.TestManagementTunnel,
                CloudflareScreenAction.TestManagementTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `controller allows another management tunnel test after equal round trip result refresh`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var managementRoundTrip = "HTTP 200"
        var managementRoundTripVersion = 1L
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true, managementHostname = "management.example.test") },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { managementRoundTrip },
                managementApiRoundTripVersionProvider = { managementRoundTripVersion },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(listOf(CloudflareScreenAction.TestManagementTunnel), actions)
        assertFalse(CloudflareScreenAction.TestManagementTunnel in controller.state.availableActions)

        managementRoundTrip = "HTTP 200"
        managementRoundTripVersion = 2L
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.TestManagementTunnel,
                CloudflareScreenAction.TestManagementTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `connected tunnel requires management hostname before exposing management tunnel test action`() {
        val missingHostnameState =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
            )
        val configuredHostnameState =
            CloudflareScreenState.from(
                config =
                    enabledCloudflareConfig(tokenPresent = true).copy(
                        cloudflare =
                            enabledCloudflareConfig(tokenPresent = true).cloudflare.copy(
                                managementHostnameLabel = "management.example.test",
                            ),
                    ),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
            )

        assertFalse(CloudflareScreenAction.TestManagementTunnel in missingHostnameState.availableActions)
        assertTrue(CloudflareScreenAction.CopyDiagnostics in missingHostnameState.availableActions)
        assertTrue(CloudflareScreenAction.TestManagementTunnel in configuredHostnameState.availableActions)
    }

    @Test
    fun `connected tunnel does not expose management tunnel test action for unsafe hostname without host`() {
        val state =
            CloudflareScreenState.from(
                config =
                    enabledCloudflareConfig(
                        tokenPresent = true,
                        managementHostname = "https://operator:hostname-secret@",
                    ),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
            )

        assertEquals("https://", state.managementHostname)
        assertFalse(CloudflareScreenAction.TestManagementTunnel in state.availableActions)
        assertTrue(CloudflareScreenAction.CopyDiagnostics in state.availableActions)
    }

    @Test
    fun `management hostname display and diagnostics strip unsafe url details`() {
        val state =
            CloudflareScreenState.from(
                config =
                    enabledCloudflareConfig(
                        tokenPresent = true,
                        managementHostname =
                            "https://operator:hostname-secret@management.example.test/private?token=query-secret#fragment-secret",
                    ),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
            )

        assertEquals("https://management.example.test", state.managementHostname)
        assertTrue(state.copyableDiagnostics.contains("Management hostname: https://management.example.test"))
        assertFalse(state.copyableDiagnostics.contains("operator"))
        assertFalse(state.copyableDiagnostics.contains("hostname-secret"))
        assertFalse(state.copyableDiagnostics.contains("query-secret"))
        assertFalse(state.copyableDiagnostics.contains("fragment-secret"))
        assertFalse(state.copyableDiagnostics.contains("/private"))
    }

    @Test
    fun `connected tunnel exposes reconnect action with duplicate protection`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var tunnelStatus = CloudflareTunnelStatus.connected()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action -> actions += action },
            )

        assertTrue(CloudflareScreenAction.ReconnectTunnel in controller.state.availableActions)

        controller.handle(CloudflareScreenEvent.ReconnectTunnel)
        controller.handle(CloudflareScreenEvent.ReconnectTunnel)

        assertEquals(listOf(CloudflareScreenAction.ReconnectTunnel), actions)
        assertFalse(CloudflareScreenAction.ReconnectTunnel in controller.state.availableActions)

        tunnelStatus = CloudflareTunnelStatus.starting()
        controller.handle(CloudflareScreenEvent.Refresh)
        tunnelStatus = CloudflareTunnelStatus.degraded()
        controller.handle(CloudflareScreenEvent.Refresh)
        controller.handle(CloudflareScreenEvent.ReconnectTunnel)

        assertEquals(
            listOf(
                CloudflareScreenAction.ReconnectTunnel,
                CloudflareScreenAction.ReconnectTunnel,
            ),
            actions,
        )
    }

    @Test
    fun `pending tunnel lifecycle operation suppresses other lifecycle actions until resolved`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        var tunnelStatus = CloudflareTunnelStatus.connected()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                actionHandler = { action -> actions += action },
            )

        controller.handle(CloudflareScreenEvent.ReconnectTunnel)

        assertFalse(CloudflareScreenAction.StopTunnel in controller.state.availableActions)
        assertTrue(CloudflareScreenAction.CopyDiagnostics in controller.state.availableActions)

        controller.handle(CloudflareScreenEvent.StopTunnel)

        assertEquals(listOf(CloudflareScreenAction.ReconnectTunnel), actions)

        tunnelStatus = CloudflareTunnelStatus.degraded()
        controller.handle(CloudflareScreenEvent.Refresh)

        assertTrue(CloudflareScreenAction.StopTunnel in controller.state.availableActions)
    }

    @Test
    fun `controller exposes pending management tunnel test as visible state until resolved`() {
        var managementRoundTripVersion = 1L
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true, managementHostname = "management.example.test") },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                managementApiRoundTripProvider = { "HTTP 200" },
                managementApiRoundTripVersionProvider = { managementRoundTripVersion },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals("In progress: Test management tunnel", controller.state.pendingOperation)
        assertEquals(setOf(CloudflareScreenWarning.OperationInProgress), controller.state.warnings)
        assertTrue(controller.state.copyableDiagnostics.contains("Warnings: Cloudflare operation in progress"))

        managementRoundTripVersion = 2L
        controller.handle(CloudflareScreenEvent.Refresh)

        assertEquals("None", controller.state.pendingOperation)
        assertEquals(emptySet(), controller.state.warnings)
    }

    @Test
    fun `controller emits audit records for dispatched cloudflare actions`() {
        val actions = mutableListOf<CloudflareScreenAction>()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true, managementHostname = "management.example.test") },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                actionHandler = { action -> actions += action },
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 1234L },
            )

        controller.handle(CloudflareScreenEvent.TestManagementTunnel)

        assertEquals(listOf(CloudflareScreenAction.TestManagementTunnel), actions)
        assertEquals(
            listOf(
                CloudflareScreenEffect.RecordAuditAction(
                    PersistedLogsAuditRecord(
                        occurredAtEpochMillis = 1234L,
                        category = LogsAuditRecordCategory.CloudflareTunnel,
                        severity = LogsAuditRecordSeverity.Info,
                        title = "Cloudflare test_management_tunnel",
                        detail = "action=test_management_tunnel lifecycle=Connected",
                    ),
                ),
            ),
            controller.consumeEffects(),
        )
    }

    @Test
    fun `controller emits redacted diagnostics copy effect once`() {
        val controller =
            CloudflareScreenController(
                configProvider = {
                    enabledCloudflareConfig(tokenPresent = true).copy(
                        cloudflare =
                            enabledCloudflareConfig(tokenPresent = true).cloudflare.copy(
                                managementHostnameLabel = "https://example.test/manage?token=tunnel-secret",
                            ),
                    )
                },
                tunnelStatusProvider = {
                    CloudflareTunnelStatus.failed(
                        "Authorization: Bearer tunnel-secret\nhttps://example.test/api/status?token=tunnel-secret",
                    )
                },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                managementApiRoundTripProvider = { "HTTP 503 for token=tunnel-secret" },
                secrets = LogRedactionSecrets(cloudflareTunnelToken = "tunnel-secret"),
            )

        controller.handle(CloudflareScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as CloudflareScreenEffect.CopyText).text
        assertTrue(copyText.contains("Tunnel lifecycle: Failed"))
        assertFalse(copyText.contains("tunnel-secret"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller copy diagnostics emits metadata only audit effect when enabled`() {
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { CloudflareTunnelStatus.connected() },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                secrets = LogRedactionSecrets(cloudflareTunnelToken = "tunnel-secret"),
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 321L },
            )

        controller.handle(CloudflareScreenEvent.CopyDiagnostics)

        val effects = controller.consumeEffects()
        assertEquals(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = 321L,
                category = LogsAuditRecordCategory.CloudflareTunnel,
                severity = LogsAuditRecordSeverity.Info,
                title = "Cloudflare copy_diagnostics",
                detail = "action=copy_diagnostics lifecycle=Connected",
            ),
            effects.filterIsInstance<CloudflareScreenEffect.RecordAuditAction>().single().record,
        )
        assertTrue(effects.any { effect -> effect is CloudflareScreenEffect.CopyText })
        assertFalse(effects.joinToString(separator = "\n").contains("tunnel-secret"))
    }

    @Test
    fun `controller lifecycle audit records request time tunnel state before handler mutates it`() {
        var tunnelStatus = CloudflareTunnelStatus.stopped()
        val controller =
            CloudflareScreenController(
                configProvider = { enabledCloudflareConfig(tokenPresent = true) },
                tunnelStatusProvider = { tunnelStatus },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 654L },
                actionHandler = { action ->
                    if (action == CloudflareScreenAction.StartTunnel) {
                        tunnelStatus = CloudflareTunnelStatus.starting()
                    }
                },
            )

        controller.handle(CloudflareScreenEvent.StartTunnel)

        assertEquals(
            PersistedLogsAuditRecord(
                occurredAtEpochMillis = 654L,
                category = LogsAuditRecordCategory.CloudflareTunnel,
                severity = LogsAuditRecordSeverity.Info,
                title = "Cloudflare start_tunnel",
                detail = "action=start_tunnel lifecycle=Stopped",
            ),
            controller
                .consumeEffects()
                .filterIsInstance<CloudflareScreenEffect.RecordAuditAction>()
                .single()
                .record,
        )
    }

    @Test
    fun `failed tunnel exposes sanitized last connection error category`() {
        val state =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus =
                    CloudflareTunnelStatus.failed(
                        "edge-session-timeout: Authorization Bearer tunnel-secret",
                    ),
                tokenStatus = CloudflareTokenStatus.Present,
                secrets = LogRedactionSecrets(cloudflareTunnelToken = "tunnel-secret"),
            )

        assertEquals("edge-session-timeout", state.lastConnectionError)
        assertEquals("Local proxy remains usable", state.localProxyImpact)
        assertTrue(state.copyableDiagnostics.contains("Last connection error: edge-session-timeout"))
        assertTrue(state.copyableDiagnostics.contains("Local proxy impact: Local proxy remains usable"))
        assertFalse(state.copyableDiagnostics.contains("tunnel-secret"))
    }

    @Test
    fun `controller refreshes redaction secrets before copying diagnostics`() {
        var secrets = LogRedactionSecrets()
        val controller =
            CloudflareScreenController(
                configProvider = {
                    enabledCloudflareConfig(tokenPresent = true).copy(
                        cloudflare =
                            enabledCloudflareConfig(tokenPresent = true).cloudflare.copy(
                                managementHostnameLabel = "https://example.test/manage?token=fresh-secret",
                            ),
                    )
                },
                tunnelStatusProvider = {
                    CloudflareTunnelStatus.failed("Authorization: Bearer fresh-secret")
                },
                tokenStatusProvider = { CloudflareTokenStatus.Present },
                managementApiRoundTripProvider = { "HTTP 503 for token=fresh-secret" },
                secretsProvider = { secrets },
            )
        secrets = LogRedactionSecrets(cloudflareTunnelToken = "fresh-secret")

        controller.handle(CloudflareScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as CloudflareScreenEffect.CopyText).text
        assertFalse(copyText.contains("fresh-secret"))
        assertTrue(copyText.contains("[REDACTED]"))
    }

    @Test
    fun `connected tunnel with failing management round trip exposes visible warning`() {
        val failingState =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
                managementApiRoundTrip = "HTTP 503",
            )
        val passingState =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus = CloudflareTunnelStatus.connected(),
                tokenStatus = CloudflareTokenStatus.Present,
                managementApiRoundTrip = "HTTP 200",
            )

        assertEquals(
            setOf(CloudflareScreenWarning.ManagementApiRoundTripFailing),
            failingState.warnings,
        )
        assertTrue(failingState.copyableDiagnostics.contains("Warnings: Management API round trip failing"))
        assertTrue(passingState.warnings.isEmpty())
    }

    @Test
    fun `enabled cloudflare with missing tunnel token exposes visible warning`() {
        val missingState =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = false),
                tunnelStatus = CloudflareTunnelStatus.stopped(),
                tokenStatus = CloudflareTokenStatus.Missing,
            )
        val disabledState =
            CloudflareScreenState.from(
                config = AppConfig.default(),
                tunnelStatus = CloudflareTunnelStatus.disabled(),
                tokenStatus = CloudflareTokenStatus.Missing,
            )

        assertEquals(
            setOf(CloudflareScreenWarning.TunnelTokenMissing),
            missingState.warnings,
        )
        assertTrue(missingState.copyableDiagnostics.contains("Warnings: Cloudflare tunnel token missing"))
        assertTrue(disabledState.warnings.isEmpty())
    }

    @Test
    fun `enabled cloudflare with invalid tunnel token exposes visible warning`() {
        val invalidState =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus = CloudflareTunnelStatus.stopped(),
                tokenStatus = CloudflareTokenStatus.Invalid,
            )
        val disabledState =
            CloudflareScreenState.from(
                config = AppConfig.default(),
                tunnelStatus = CloudflareTunnelStatus.disabled(),
                tokenStatus = CloudflareTokenStatus.Invalid,
            )

        assertEquals(
            setOf(CloudflareScreenWarning.TunnelTokenInvalid),
            invalidState.warnings,
        )
        assertTrue(invalidState.copyableDiagnostics.contains("Warnings: Cloudflare tunnel token invalid"))
        assertTrue(disabledState.warnings.isEmpty())
    }

    @Test
    fun `degraded cloudflare tunnel exposes visible warning and copy diagnostics entry`() {
        val state =
            CloudflareScreenState.from(
                config = enabledCloudflareConfig(tokenPresent = true),
                tunnelStatus = CloudflareTunnelStatus.degraded(),
                tokenStatus = CloudflareTokenStatus.Present,
            )

        assertEquals(
            setOf(CloudflareScreenWarning.TunnelDegraded),
            state.warnings,
        )
        assertTrue(state.copyableDiagnostics.contains("Warnings: Cloudflare tunnel degraded"))
    }

    @Test
    fun `cloudflare token status distinguishes missing invalid and valid stored tokens`() {
        val validTunnelToken =
            "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="

        assertEquals(CloudflareTokenStatus.Missing, cloudflareTokenStatusFrom(null))
        assertEquals(CloudflareTokenStatus.Missing, cloudflareTokenStatusFrom(""))
        assertEquals(CloudflareTokenStatus.Invalid, cloudflareTokenStatusFrom("not-a-tunnel-token"))
        assertEquals(CloudflareTokenStatus.Invalid, cloudflareTokenStatusFrom("  $validTunnelToken  "))
        assertEquals(
            CloudflareTokenStatus.Present,
            cloudflareTokenStatusFrom(validTunnelToken),
        )
    }

    @Test
    fun `cloudflare token status projects safely from sensitive config load results`() {
        val validTunnelToken =
            "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="

        assertEquals(
            CloudflareTokenStatus.Present,
            cloudflareTokenStatusFrom(
                SensitiveConfigLoadResult.Loaded(
                    SensitiveConfig(
                        proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                        managementApiToken = "management-token",
                        cloudflareTunnelToken = validTunnelToken,
                    ),
                ),
            ),
        )
        assertEquals(
            CloudflareTokenStatus.Missing,
            cloudflareTokenStatusFrom(SensitiveConfigLoadResult.MissingRequiredSecrets),
        )
        assertEquals(
            CloudflareTokenStatus.Invalid,
            cloudflareTokenStatusFrom(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ),
        )
    }

    private fun enabledCloudflareConfig(
        tokenPresent: Boolean,
        managementHostname: String? = null,
    ): AppConfig {
        val defaultConfig = AppConfig.default()
        return defaultConfig.copy(
            cloudflare =
                defaultConfig.cloudflare.copy(
                    enabled = true,
                    tunnelTokenPresent = tokenPresent,
                    managementHostnameLabel = managementHostname,
                ),
        )
    }
}
