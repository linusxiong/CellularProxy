package com.cellularproxy.app.ui

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyStatusProviderTest {
    @Test
    fun `uses live management api status when it is available`() {
        val liveStatus = ProxyServiceStatus(state = ProxyServiceState.Starting)

        val status =
            proxyStatusFromLiveStatusOrConfigFallback(
                config = config(),
                liveStatus = { liveStatus },
            )

        assertEquals(liveStatus, status)
    }

    @Test
    fun `falls back to configured stopped route when live management api status is unavailable`() {
        val status =
            proxyStatusFromLiveStatusOrConfigFallback(
                config = config(),
                liveStatus = { null },
            )

        assertEquals(ProxyServiceState.Stopped, status.state)
        assertEquals(RouteTarget.Cellular, status.configuredRoute)
    }

    @Test
    fun `falls back to configured stopped route when live management api status throws`() {
        val status =
            proxyStatusFromLiveStatusOrConfigFallback(
                config = config(),
                liveStatus = { error("connection refused") },
            )

        assertEquals(ProxyServiceState.Stopped, status.state)
        assertEquals(RouteTarget.Cellular, status.configuredRoute)
    }

    @Test
    fun `safe sensitive config helper creates defaults only for missing storage`() {
        var createDefaultInvoked = false
        val defaultSensitiveConfig = sensitiveConfig("generated-management-token")

        assertEquals(
            defaultSensitiveConfig,
            sensitiveConfigFromLoadResultOrCreateDefault(SensitiveConfigLoadResult.MissingRequiredSecrets) {
                createDefaultInvoked = true
                defaultSensitiveConfig
            },
        )
        assertTrue(createDefaultInvoked)

        createDefaultInvoked = false
        val loadedSensitiveConfig = sensitiveConfig("loaded-management-token")
        assertEquals(
            loadedSensitiveConfig,
            sensitiveConfigFromLoadResultOrCreateDefault(SensitiveConfigLoadResult.Loaded(loadedSensitiveConfig)) {
                createDefaultInvoked = true
                defaultSensitiveConfig
            },
        )
        assertFalse(createDefaultInvoked)

        assertNull(
            sensitiveConfigFromLoadResultOrCreateDefault(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
            ) {
                createDefaultInvoked = true
                defaultSensitiveConfig
            },
        )
        assertFalse(createDefaultInvoked)
    }

    @Test
    fun `app shell refreshes live proxy status off the compose state construction path`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            shellSource.contains("var proxyStatusState by remember"),
            "App shell must hold a cached proxy status state for synchronous UI consumers.",
        )
        assertTrue(
            shellSource.contains("LaunchedEffect(") &&
                shellSource.contains("withContext(Dispatchers.IO)") &&
                Regex("""localManagementApiStatusReader\s+\.loadSnapshot\(""")
                    .containsMatchIn(shellSource),
            "Live Management API status refresh must run from a coroutine on Dispatchers.IO.",
        )
        val refreshProxyStatusBlock =
            assertNotNull(
                Regex("""val refreshProxyStatus: suspend \(\) -> Unit = \{([\s\S]*?)\n    }""")
                    .find(shellSource),
            ).groupValues[1]
        assertTrue(
            refreshProxyStatusBlock.contains("sensitiveConfigFromLoadResultOrCreateDefault(loadSensitiveConfigResult())") &&
                !refreshProxyStatusBlock.contains("sensitiveConfig = loadSensitiveConfig()"),
            "Live status refresh must avoid the throwing sensitive-config loader when encrypted storage is invalid.",
        )
        assertTrue(
            shellSource.contains("val loadProxyStatus: () -> ProxyServiceStatus = {\n        proxyStatusState\n    }"),
            "The Compose status provider must return cached state instead of performing blocking HTTP work.",
        )
    }

    @Test
    fun `app shell updates cached rotation status from live management api status refresh`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            Regex("""localManagementApiStatusReader\s+\.loadSnapshot\(""").containsMatchIn(shellSource) &&
                shellSource.contains("refreshedRotationStatus = snapshot.rotationStatus") &&
                shellSource.contains("rotationStatusState = rotationStatus"),
            "The shared live status refresh must update Rotation state from GET /api/status, not only rotation action responses.",
        )
    }

    @Test
    fun `app shell refreshes cached live status after management api actions`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val cloudflareSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyCloudflareScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("val refreshProxyStatus: suspend () -> Unit"),
            "App shell must expose a single refresh path for the cached live proxy status.",
        )
        assertTrue(
            Regex(
                """localManagementApiActionDispatcher\.dispatch\([\s\S]*?\.onFailure \{ throwable ->[\s\S]*?refreshProxyStatus\(\)""",
            ).containsMatchIn(shellSource),
            "Management API actions must refresh cached proxy status after dispatch so provider-backed screens do not stay on the first snapshot.",
        )
    }

    @Test
    fun `dashboard refresh status action refreshes cached live proxy status`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val dashboardSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyDashboardScreen.kt")
                .readText()

        assertTrue(
            dashboardSource.contains("onRefreshStatus: () -> Unit = {}"),
            "Dashboard route must expose an explicit refresh callback for its visible Refresh status action.",
        )
        assertTrue(
            dashboardSource.contains("DashboardScreenAction.RefreshStatus -> currentOnRefreshStatus()"),
            "Dashboard RefreshStatus must invoke the injected refresh callback instead of falling through to a no-op.",
        )
        assertTrue(
            shellSource.contains("onRefreshStatus = onRefreshProxyStatus") &&
                Regex("""onRefreshProxyStatus = \{\s+coroutineScope\.launch \{ refreshProxyStatus\(\) }\s+},""")
                    .containsMatchIn(shellSource),
            "The launched Dashboard Refresh status action must refresh the cached live Management API status.",
        )
    }

    @Test
    fun `foreground service dashboard start action refreshes cached live proxy status after dispatch`() {
        val events = mutableListOf<String>()

        dispatchForegroundServiceCommandThenRefresh(
            action = "START_PROXY",
            dispatchForegroundServiceCommand = { action -> events += "dispatch:$action" },
            refreshProxyStatus = { events += "refresh" },
        )

        assertEquals(listOf("dispatch:START_PROXY", "refresh"), events)

        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            shellSource.contains("dispatchForegroundServiceCommandThenRefresh(") &&
                shellSource.contains("DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS") &&
                shellSource.contains("delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)") &&
                shellSource.contains("ForegroundServiceActions.START_PROXY"),
            "Dashboard start service action must dispatch the command and refresh cached live status after service delivery has time to complete.",
        )
    }

    @Test
    fun `dashboard service stop schedules delayed cached live status refresh after management api response`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            Regex(
                """onStopProxyService = \{[\s\S]*?dispatchLocalManagementApiAction\([\s\S]*?LocalManagementApiAction\.ServiceStop[\s\S]*?afterResponse = \{[\s\S]*?coroutineScope\.launch \{[\s\S]*?delay\(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS\)[\s\S]*?refreshProxyStatus\(\)""",
            ).findAll(shellSource).count() == 2,
            "Both Dashboard service stop handlers must schedule a delayed live-status refresh after the Management API stop response.",
        )
    }

    @Test
    fun `dashboard service restart schedules delayed cached live status refresh after management api response`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            Regex(
                """onRestartProxyService = \{[\s\S]*?dispatchLocalManagementApiAction\([\s\S]*?LocalManagementApiAction\.ServiceRestart[\s\S]*?afterResponse = \{[\s\S]*?coroutineScope\.launch \{[\s\S]*?delay\(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS\)[\s\S]*?refreshProxyStatus\(\)""",
            ).findAll(shellSource).count() == 2,
            "Both Dashboard service restart handlers must schedule a delayed live-status refresh because the root restart runs after the Management API response is sent.",
        )
    }

    @Test
    fun `cloudflare management tunnel test result is cached for Cloudflare screen state`() {
        assertEquals(
            "HTTP 200",
            cloudflareManagementRoundTripSummary(
                action = LocalManagementApiAction.CloudflareManagementStatus,
                response = LocalManagementApiActionResponse(statusCode = 200),
            ),
        )
        assertEquals(
            "HTTP 503",
            cloudflareManagementRoundTripSummary(
                action = LocalManagementApiAction.CloudflareManagementStatus,
                response = LocalManagementApiActionResponse(statusCode = 503),
            ),
        )
        assertEquals(
            "Request failed",
            cloudflareManagementRoundTripFailureSummary(LocalManagementApiAction.CloudflareManagementStatus),
        )
        assertNull(
            cloudflareManagementRoundTripSummary(
                action = LocalManagementApiAction.CloudflareStart,
                response = LocalManagementApiActionResponse(statusCode = 200),
            ),
        )
        assertNull(cloudflareManagementRoundTripFailureSummary(LocalManagementApiAction.CloudflareStart))

        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val cloudflareSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyCloudflareScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("var cloudflareManagementRoundTripState by remember"),
            "App shell must cache the latest Cloudflare management tunnel test result for screen state.",
        )
        assertTrue(
            shellSource.contains("cloudflareManagementRoundTripSummary(") &&
                shellSource.contains("cloudflareManagementRoundTripFailureSummary("),
            "App shell must update the Cloudflare round-trip result for both HTTP responses and request failures.",
        )
        assertTrue(
            shellSource.contains("cloudflareManagementRoundTripProvider = { cloudflareManagementRoundTripState }") &&
                shellSource.contains("managementApiRoundTripProvider = cloudflareManagementRoundTripProvider"),
            "Launched Cloudflare route must display the cached management tunnel test result.",
        )
        assertTrue(
            cloudflareSource.contains("val observedManagementApiRoundTrip = managementApiRoundTripProvider()") &&
                cloudflareSource.contains(
                    "LaunchedEffect(\n" +
                        "        observedConfig,\n" +
                        "        observedTokenStatus,\n" +
                        "        observedTunnelStatus,\n" +
                        "        observedEdgeSessionSummary,\n" +
                        "        observedManagementApiRoundTrip,\n" +
                        "        observedManagementApiRoundTripVersion,\n" +
                        "        observedRedactionSecrets,\n" +
                        "    )",
                ) &&
                cloudflareSource.contains("controller.handle(CloudflareScreenEvent.Refresh)"),
            "Cloudflare route must refresh its remembered screen state when the management round-trip provider changes.",
        )
    }

    @Test
    fun `cloudflare management api probe result maps to dashboard risk check state`() {
        assertEquals(
            DashboardCloudflareManagementApiCheck.NotRun,
            CloudflareManagementApiProbeResult.NotConfigured.toDashboardCloudflareManagementApiCheck(),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Passed,
            CloudflareManagementApiProbeResult.Authenticated.toDashboardCloudflareManagementApiCheck(),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Failed,
            CloudflareManagementApiProbeResult.Unavailable.toDashboardCloudflareManagementApiCheck(),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Failed,
            CloudflareManagementApiProbeResult.Unauthorized.toDashboardCloudflareManagementApiCheck(),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Failed,
            CloudflareManagementApiProbeResult.Error.toDashboardCloudflareManagementApiCheck(),
        )
    }

    @Test
    fun `dashboard cloudflare management api check uses probe configuration before request failures`() {
        assertEquals(
            DashboardCloudflareManagementApiCheck.NotRun,
            dashboardCloudflareManagementApiCheckFrom(
                config = config().copy(cloudflare = CloudflareConfig(enabled = false)),
                tunnelTokenPresent = true,
                request = { error("not configured") },
            ),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Passed,
            dashboardCloudflareManagementApiCheckFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 204) },
            ),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Failed,
            dashboardCloudflareManagementApiCheckFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { LocalManagementApiActionResponse(statusCode = 503) },
            ),
        )
        assertEquals(
            DashboardCloudflareManagementApiCheck.Failed,
            dashboardCloudflareManagementApiCheckFrom(
                config = configuredCloudflare(),
                tunnelTokenPresent = true,
                request = { error("connection refused") },
            ),
        )
    }
}

private fun config(): AppConfig = AppConfig(
    proxy = ProxyConfig(),
    network = NetworkConfig(defaultRoutePolicy = RouteTarget.Cellular),
    rotation = RotationConfig(),
    cloudflare = CloudflareConfig(),
)

private fun configuredCloudflare(): AppConfig = AppConfig.default().copy(
    cloudflare =
        CloudflareConfig(
            enabled = true,
            tunnelTokenPresent = true,
            managementHostnameLabel = "management.example.test",
        ),
)

private fun sensitiveConfig(managementApiToken: String): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = managementApiToken,
)

private fun repoRoot() = generateSequence(Path(requireNotNull(System.getProperty("user.dir")))) { path -> path.parent }
    .first { path -> path.resolve("settings.gradle.kts").toFile().exists() }
