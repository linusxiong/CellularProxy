package com.cellularproxy.app.ui

import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `app shell refreshes live proxy status off the compose state construction path`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val cloudflareSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyCloudflareScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("var proxyStatusState by remember"),
            "App shell must hold a cached proxy status state for synchronous UI consumers.",
        )
        assertTrue(
            shellSource.contains("LaunchedEffect(") &&
                shellSource.contains("withContext(Dispatchers.IO)") &&
                shellSource.contains("localManagementApiStatusReader.load("),
            "Live Management API status refresh must run from a coroutine on Dispatchers.IO.",
        )
        assertTrue(
            shellSource.contains("val loadProxyStatus: () -> ProxyServiceStatus = {\n        proxyStatusState\n    }"),
            "The Compose status provider must return cached state instead of performing blocking HTTP work.",
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
                cloudflareSource.contains("LaunchedEffect(observedManagementApiRoundTrip)") &&
                cloudflareSource.contains("controller.handle(CloudflareScreenEvent.Refresh)"),
            "Cloudflare route must refresh its remembered screen state when the management round-trip provider changes.",
        )
    }
}

private fun config(): AppConfig = AppConfig(
    proxy = ProxyConfig(),
    network = NetworkConfig(defaultRoutePolicy = RouteTarget.Cellular),
    rotation = RotationConfig(),
    cloudflare = CloudflareConfig(),
)

private fun repoRoot() = generateSequence(Path(requireNotNull(System.getProperty("user.dir")))) { path -> path.parent }
    .first { path -> path.resolve("settings.gradle.kts").toFile().exists() }
