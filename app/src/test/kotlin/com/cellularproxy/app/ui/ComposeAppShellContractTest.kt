package com.cellularproxy.app.ui

import com.cellularproxy.app.diagnostics.DiagnosticCheckType
import com.cellularproxy.app.diagnostics.DiagnosticResultItem
import com.cellularproxy.app.diagnostics.DiagnosticResultStatus
import com.cellularproxy.app.diagnostics.DiagnosticRunRecord
import com.cellularproxy.app.diagnostics.DiagnosticsResultModel
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.app.status.DashboardLogSeverity
import com.cellularproxy.app.status.DashboardLogSummary
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.status.DashboardWarning
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ComposeAppShellContractTest {
    @Test
    fun `app module is configured for Compose`() {
        val rootBuild = repoRoot().resolve("build.gradle.kts").readText()
        val appBuild = repoRoot().resolve("app/build.gradle.kts").readText()

        assertTrue(
            rootBuild.contains("org.jetbrains.kotlin.plugin.compose"),
            "Root build must declare the Kotlin Compose compiler plugin.",
        )
        assertTrue(
            appBuild.contains("org.jetbrains.kotlin.plugin.compose"),
            "App build must apply the Kotlin Compose compiler plugin.",
        )
        assertTrue(appBuild.contains("buildFeatures"), "App build must enable Android Compose build features.")
        assertTrue(appBuild.contains("compose = true"), "App build must enable Compose.")
        assertTrue(appBuild.contains("androidx.activity:activity-compose"), "App build must include Activity Compose.")
        assertTrue(appBuild.contains("androidx.compose.material3:material3"), "App build must include Material3.")
        assertTrue(
            appBuild.contains("androidx.navigation:navigation-compose"),
            "App build must include Navigation Compose for the operator console graph.",
        )
        assertTrue(
            appBuild.contains("androidx.compose.material:material-icons-extended"),
            "App build must include Compose Material icons for recognizable navigation controls.",
        )
    }

    @Test
    fun `launcher activity hosts the Compose app shell`() {
        val activitySource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyActivity.kt")
                .readText()
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            activitySource.contains("ComponentActivity"),
            "Launcher activity must use ComponentActivity as the Compose host.",
        )
        assertTrue(activitySource.contains("setContent"), "Launcher activity must install Compose content.")
        assertTrue(
            activitySource.contains("CellularProxyApp()"),
            "Launcher activity must delegate UI composition to the app shell.",
        )
        assertTrue(shellSource.contains("Scaffold"), "Compose app shell must provide the operator console scaffold.")
        assertTrue(shellSource.contains("CellularProxy"), "Compose app shell must render the product name.")
        assertFalse(
            activitySource.contains("android.widget.") ||
                activitySource.contains("createContentView") ||
                activitySource.contains("LinearLayout") ||
                activitySource.contains("setContentView") ||
                activitySource.contains("findViewById") ||
                activitySource.contains("android.view.") ||
                activitySource.contains("layoutInflater") ||
                activitySource.contains("ViewBinding") ||
                activitySource.contains("DataBinding"),
            "Launcher activity must not retain the legacy native View settings/dashboard UI.",
        )
    }

    @Test
    fun `app shell declares and routes all top level operator destinations`() {
        val destinations =
            CellularProxyNavigationDestination.entries
                .map { destination -> destination.route to destination.label }

        assertEquals(
            listOf(
                "dashboard" to "Dashboard",
                "settings" to "Settings",
                "cloudflare" to "Cloudflare",
                "rotation" to "Rotation",
                "diagnostics" to "Diagnostics",
                "logs-audit" to "Logs/Audit",
            ),
            destinations,
        )

        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(shellSource.contains("NavHost"), "Compose shell must own a top-level navigation graph.")
        assertTrue(
            shellSource.contains("CellularProxyNavigationDestination.Dashboard.route"),
            "Dashboard must be the navigation graph start destination.",
        )
        CellularProxyNavigationDestination.entries.forEach { destination ->
            assertTrue(
                shellSource.contains("composable(${destination.name}.route)"),
                "Missing route wiring for ${destination.label}.",
            )
        }
    }

    @Test
    fun `app shell exposes top level navigation controls`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertTrue(
            shellSource.contains("NavigationBar"),
            "Compose shell must expose top-level navigation controls.",
        )
        assertTrue(
            shellSource.contains("NavigationBarItem"),
            "Top-level navigation controls must render one item per destination.",
        )
        assertTrue(
            shellSource.contains("currentBackStackEntryAsState"),
            "Top-level navigation controls must track the currently selected route.",
        )
        assertTrue(
            shellSource.contains("navController.navigate(destination.route)"),
            "Top-level navigation items must navigate to their destination route.",
        )
        assertTrue(
            shellSource.contains("Icon(destination.icon, contentDescription = null)"),
            "Top-level navigation items must render a recognizable destination icon.",
        )
        assertFalse(
            shellSource.contains("icon = {}"),
            "Top-level navigation items must not render blank icon slots.",
        )
        assertTrue(
            shellSource.contains("launchSingleTop = true"),
            "Top-level navigation should avoid stacking duplicate destination copies.",
        )
        assertTrue(
            shellSource.contains("popUpTo(navController.graph.findStartDestination().id)"),
            "Top-level navigation should pop to the graph start destination instead of stacking tab history.",
        )
        assertTrue(
            shellSource.contains("saveState = true"),
            "Top-level navigation should save destination state when switching tabs.",
        )
        assertTrue(
            shellSource.contains("restoreState = true"),
            "Top-level navigation should restore destination state when returning to a tab.",
        )
    }

    @Test
    fun `app shell adapts top level navigation chrome for wide screens`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()

        assertEquals(
            CellularProxyNavigationChrome.BottomBar,
            cellularProxyNavigationChromeFor(599),
            "Compact screens should keep bottom navigation.",
        )
        assertEquals(
            CellularProxyNavigationChrome.NavigationRail,
            cellularProxyNavigationChromeFor(600),
            "Medium-width screens should switch to navigation rail.",
        )
        assertEquals(
            CellularProxyNavigationChrome.NavigationRail,
            cellularProxyNavigationChromeFor(840),
            "Expanded screens should keep navigation rail.",
        )

        assertTrue(
            shellSource.contains("BoxWithConstraints"),
            "Compose shell must inspect available width for adaptive navigation chrome.",
        )
        assertTrue(
            shellSource.contains("NavigationRail"),
            "Wide screens must use a navigation rail instead of only a phone bottom bar.",
        )
        assertTrue(
            shellSource.contains("cellularProxyNavigationChromeFor(maxWidth.value.toInt())"),
            "Compose shell must use the tested adaptive navigation breakpoint helper.",
        )
        assertTrue(
            shellSource.contains("CellularProxyNavigationRail(navController)"),
            "Wide layout must render the same top-level destinations through a navigation rail.",
        )
    }

    @Test
    fun `dashboard route renders dedicated status screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val dashboardSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyDashboardScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("CellularProxyDashboardRoute("),
            "Dashboard route must render the dedicated status route instead of the generic placeholder.",
        )
        assertTrue(
            !shellSource.contains("composable(Dashboard.route) {\n            CellularProxyDestinationPlaceholder(Dashboard)"),
            "Dashboard route must not use the generic destination placeholder.",
        )

        listOf(
            "Service state",
            "Proxy endpoint",
            "Selected route",
            "Proxy authentication",
            "Management API",
            "Cloudflare tunnel",
            "Root availability",
            "Public IP",
            "Active connections",
            "Recent traffic",
            "Total traffic",
            "Recent high-severity errors",
            "Start proxy",
            "Stop proxy",
            "Refresh status",
            "Copy proxy endpoint",
            "Open risk details",
            "Open Cloudflare",
            "Open rotation",
            "Open logs",
            "Open diagnostics",
        ).forEach { label ->
            assertTrue(
                dashboardSource.contains(label),
                "Dashboard screen must expose `$label`.",
            )
        }
        assertTrue(
            shellSource.contains("CellularProxyDashboardRoute("),
            "Dashboard route must render through the controller-backed route.",
        )
        assertTrue(
            shellSource.contains("statusProvider = {"),
            "Dashboard app-shell wiring must provide repository-backed status instead of the default stopped state.",
        )
        assertTrue(
            dashboardSource.contains("statusProvider: () -> DashboardStatusModel"),
            "Dashboard route must accept an injectable status provider for app-shell runtime data.",
        )
        assertTrue(
            dashboardSource.contains("statusProvider = { currentStatusProvider() }"),
            "Dashboard route controller must be backed by the injected status provider.",
        )
        assertTrue(
            dashboardSource.contains("DashboardField(\"Recent traffic\", recentTrafficSummary(status))"),
            "Dashboard must render recent traffic from the explicit recent-traffic summary.",
        )
        assertTrue(
            dashboardSource.contains("DashboardField(\"Total traffic\", totalTrafficSummary(status))"),
            "Dashboard must keep cumulative traffic counters under a total-traffic label.",
        )
        assertTrue(
            shellSource.contains("DashboardRecentTrafficSampler(") &&
                shellSource.contains("nowElapsedMillis = SystemClock::elapsedRealtime"),
            "App shell must create a live recent-traffic sampler instead of leaving recent traffic permanently unavailable.",
        )
        assertTrue(
            shellSource.contains("recentTrafficState = recentTrafficSampler.observe(refreshedProxyStatus.metrics)") &&
                shellSource.contains("recentTrafficProvider = { recentTrafficState }"),
            "App shell must update recent traffic during live status refresh instead of mutating sampler state during dashboard reads.",
        )
        assertTrue(
            shellSource.contains("recentTraffic = recentTrafficProvider()"),
            "Dashboard status wiring must read the latest sampled recent traffic without advancing the sampler.",
        )
        assertTrue(
            dashboardSource.contains("} ?: \"Unavailable\""),
            "Dashboard recent traffic must not fall back to cumulative lifetime counters.",
        )
        assertTrue(
            dashboardSource.contains("rememberUpdatedState(statusProvider)") &&
                dashboardSource.contains("currentStatusProvider()"),
            "Dashboard route must preserve the latest status provider across recomposition.",
        )
        assertTrue(
            dashboardSource.contains("val observedStatus = statusProvider()") &&
                dashboardSource.contains("LaunchedEffect(observedStatus)") &&
                dashboardSource.contains("DashboardScreenEvent.Refresh"),
            "Dashboard route must refresh remembered controller state when provider-backed status changes.",
        )
        assertTrue(
            dashboardSource.contains("DashboardScreenController("),
            "Dashboard route must use the tested screen controller boundary.",
        )
        assertTrue(
            dashboardSource.contains("var screenState by remember { mutableStateOf(controller.state) }"),
            "Dashboard route must mirror controller state into Compose state for recomposition.",
        )
        assertTrue(
            dashboardSource.contains("DashboardScreenEvent.StartProxy"),
            "Dashboard route must send start actions through the dashboard controller.",
        )
        assertTrue(
            dashboardSource.contains("DashboardScreenEvent.StopProxy"),
            "Dashboard route must send stop actions through the dashboard controller.",
        )
        assertTrue(
            dashboardSource.contains("DashboardScreenEvent.CopyProxyEndpoint"),
            "Dashboard route must send copy endpoint actions through the dashboard controller.",
        )
        assertTrue(
            dashboardSource.contains("controller.consumeEffects()"),
            "Dashboard route must consume one-shot controller effects after dispatch.",
        )
        assertTrue(
            shellSource.contains("LocalClipboardManager.current"),
            "App shell must provide a real clipboard sink for dashboard copy effects.",
        )
        assertTrue(
            shellSource.contains("clipboard.setText(AnnotatedString(endpointText))"),
            "Dashboard copy effects must be written to the Compose clipboard as text.",
        )
        assertTrue(
            shellSource.contains("LocalContext.current"),
            "App shell must provide an Android context for dashboard foreground-service commands.",
        )
        assertTrue(
            shellSource.contains("ForegroundServiceActions.START_PROXY"),
            "Dashboard start actions must use the existing foreground service start command.",
        )
        assertTrue(
            shellSource.contains("ForegroundServiceActions.STOP_PROXY"),
            "Dashboard stop actions must use the existing foreground service stop command.",
        )
        assertTrue(
            shellSource.contains("context.startForegroundService("),
            "Dashboard start/stop actions must dispatch to the Android foreground service.",
        )
        assertTrue(
            shellSource.contains("Intent(context, CellularProxyForegroundService::class.java).setAction(action)"),
            "Dashboard foreground-service dispatch must target CellularProxyForegroundService with explicit command actions.",
        )
        assertTrue(
            shellSource.contains("onOpenRiskDetails = { navController.navigate(LogsAudit.route) }"),
            "Dashboard risk-details action must navigate to a concrete detail surface instead of doing nothing.",
        )
    }

    @Test
    fun `dashboard screen state keeps risk warnings separate from recent high severity errors`() {
        val state =
            DashboardScreenState.from(
                status =
                    DashboardStatusModel.from(
                        config =
                            AppConfig.default().copy(
                                proxy =
                                    AppConfig.default().proxy.copy(
                                        authEnabled = false,
                                    ),
                            ),
                        status = ProxyServiceStatus.stopped(),
                        recentLogs =
                            listOf(
                                DashboardLogSummary(
                                    id = "failed-management",
                                    occurredAtEpochMillis = 200,
                                    severity = DashboardLogSeverity.Failed,
                                    title = "Management API failed",
                                    detail = "HTTP 503",
                                ),
                            ),
                    ),
            )

        assertEquals(
            listOf("Broad unauthenticated proxy listener"),
            state.riskWarnings,
        )
        assertEquals(
            listOf("Management API failed: HTTP 503"),
            state.recentHighSeverityErrors,
        )
        assertTrue(DashboardWarning.BroadUnauthenticatedProxy in state.status.warnings)
    }

    @Test
    fun `dashboard screen action availability follows service state`() {
        val stoppedState =
            DashboardScreenState.from(
                status =
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status = ProxyServiceStatus.stopped(),
                    ),
            )
        val runningState =
            DashboardScreenState.from(
                status =
                    DashboardStatusModel.from(
                        config = AppConfig.default(),
                        status =
                            ProxyServiceStatus.running(
                                listenHost = "127.0.0.1",
                                listenPort = 8080,
                                configuredRoute = AppConfig.default().network.defaultRoutePolicy,
                                boundRoute = null,
                                publicIp = null,
                                hasHighSecurityRisk = false,
                            ),
                    ),
            )

        assertEquals(
            listOf(
                DashboardScreenAction.StartProxy,
                DashboardScreenAction.RefreshStatus,
                DashboardScreenAction.CopyProxyEndpoint,
                DashboardScreenAction.OpenRiskDetails,
                DashboardScreenAction.OpenCloudflare,
                DashboardScreenAction.OpenRotation,
                DashboardScreenAction.OpenLogs,
                DashboardScreenAction.OpenDiagnostics,
            ),
            stoppedState.availableActions,
        )
        assertEquals(
            listOf(
                DashboardScreenAction.StopProxy,
                DashboardScreenAction.RefreshStatus,
                DashboardScreenAction.CopyProxyEndpoint,
                DashboardScreenAction.OpenRiskDetails,
                DashboardScreenAction.OpenCloudflare,
                DashboardScreenAction.OpenRotation,
                DashboardScreenAction.OpenLogs,
                DashboardScreenAction.OpenDiagnostics,
            ),
            runningState.availableActions,
        )
    }

    @Test
    fun `dashboard high impact service actions are marked for confirmation`() {
        assertFalse(DashboardScreenAction.StartProxy.requiresConfirmation)
        assertTrue(DashboardScreenAction.StopProxy.requiresConfirmation)
        assertFalse(DashboardScreenAction.RefreshStatus.requiresConfirmation)
        assertFalse(DashboardScreenAction.CopyProxyEndpoint.requiresConfirmation)
        assertFalse(DashboardScreenAction.OpenRiskDetails.requiresConfirmation)
        assertFalse(DashboardScreenAction.OpenCloudflare.requiresConfirmation)
        assertFalse(DashboardScreenAction.OpenRotation.requiresConfirmation)
        assertFalse(DashboardScreenAction.OpenLogs.requiresConfirmation)
        assertFalse(DashboardScreenAction.OpenDiagnostics.requiresConfirmation)

        assertEquals(
            "Confirm proxy service stop",
            DashboardScreenAction.StopProxy.confirmationTitle,
        )
    }

    @Test
    fun `dashboard high impact service actions require confirmation before dispatch`() {
        val dashboardSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyDashboardScreen.kt")
                .readText()

        assertEquals(
            DashboardActionDispatchMode.Immediate,
            dashboardActionDispatchMode(DashboardScreenAction.StartProxy),
        )
        assertEquals(
            DashboardActionDispatchMode.ConfirmFirst,
            dashboardActionDispatchMode(DashboardScreenAction.StopProxy),
        )
        assertEquals(
            DashboardActionDispatchMode.Immediate,
            dashboardActionDispatchMode(DashboardScreenAction.OpenCloudflare),
        )
        assertTrue(
            dashboardSource.contains("AlertDialog"),
            "Dashboard high-impact actions must show a confirmation dialog before invoking callbacks.",
        )
        assertTrue(
            dashboardSource.contains("pendingConfirmationAction"),
            "Dashboard screen must remember the action awaiting confirmation instead of dispatching it directly.",
        )
    }

    @Test
    fun `dashboard confirmation dispatch rechecks current availability`() {
        assertTrue(
            dashboardActionCanDispatch(
                action = DashboardScreenAction.StopProxy,
                actionsEnabled = true,
                availableActions = listOf(DashboardScreenAction.StopProxy),
            ),
        )
        assertFalse(
            dashboardActionCanDispatch(
                action = DashboardScreenAction.StopProxy,
                actionsEnabled = false,
                availableActions = listOf(DashboardScreenAction.StopProxy),
            ),
        )
        assertFalse(
            dashboardActionCanDispatch(
                action = DashboardScreenAction.StopProxy,
                actionsEnabled = true,
                availableActions = listOf(DashboardScreenAction.StartProxy),
            ),
        )
    }

    @Test
    fun `settings route renders dedicated configuration screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val settingsSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxySettingsScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("CellularProxySettingsRoute("),
            "Settings route must render the dedicated configuration route instead of the generic placeholder.",
        )
        assertTrue(
            shellSource.contains("CellularProxyPlainConfigStore.repository(context)") &&
                shellSource.contains("SensitiveConfigRepositoryFactory.create(context)") &&
                shellSource.contains("loadOrCreateSensitiveConfig("),
            "Settings route must be wired to the real app config and sensitive config repositories.",
        )
        assertFalse(
            shellSource.contains("CellularProxySettingsRoute()"),
            "Settings route must not use the default no-op persistence callbacks from the app shell.",
        )
        assertTrue(
            !shellSource.contains("composable(Settings.route) {\n            CellularProxyDestinationPlaceholder(Settings)"),
            "Settings route must not use the generic destination placeholder.",
        )

        listOf(
            "Listen host",
            "Listen port",
            "Proxy authentication",
            "Max concurrent connections",
            "Default route",
            "Strict IP change",
            "Mobile data off delay",
            "Network return timeout",
            "Rotation cooldown",
            "Root operations",
            "Proxy username",
            "Proxy password",
            "Management API token",
            "Cloudflare enabled",
            "Cloudflare tunnel token",
            "Cloudflare hostname",
            "Leave secret fields blank to keep current values.",
            "Save settings",
            "Discard changes",
            "Listen host must be a valid bind address.",
            "Listen port must be between 1 and 65535.",
            "Max concurrent connections must be greater than zero.",
            "Rotation timing values must be whole seconds greater than zero.",
            "Enter both proxy username and password, or leave both blank.",
            "Management API token cannot be blank or padded with spaces.",
            "Cloudflare tunnel token is missing or invalid.",
        ).forEach { label ->
            assertTrue(
                settingsSource.contains(label),
                "Settings screen must expose `$label`.",
            )
        }
        assertTrue(
            settingsSource.contains("ProxySettingsScreenController("),
            "Settings route must use the tested screen controller boundary.",
        )
        assertTrue(
            settingsSource.contains("initialConfigProvider: () -> AppConfig = AppConfig::default") &&
                settingsSource.contains("saveConfig: (AppConfig) -> Unit = {}") &&
                settingsSource.contains("loadSensitiveConfig: (() -> SensitiveConfig)? = null") &&
                settingsSource.contains("saveSensitiveConfig: ((SensitiveConfig) -> Unit)? = null"),
            "Settings route must expose callbacks for persistent plain and sensitive config wiring.",
        )
        assertTrue(
            settingsSource.contains("loadSensitiveConfigProvider = { currentLoadSensitiveConfig }") &&
                settingsSource.contains("saveSensitiveConfigProvider = { currentSaveSensitiveConfig }"),
            "Settings route controller must use the injected sensitive config callbacks.",
        )
        assertTrue(
            settingsSource.contains("var screenState by remember { mutableStateOf(controller.state) }"),
            "Settings route must mirror controller state into Compose state for recomposition.",
        )
        assertTrue(
            settingsSource.contains("rememberUpdatedState(initialConfigProvider)") &&
                settingsSource.contains("val observedConfig = initialConfigProvider()") &&
                settingsSource.contains("LaunchedEffect(observedConfig)") &&
                settingsSource.contains("ProxySettingsScreenEvent.Refresh"),
            "Settings route must refresh remembered controller state when provider-backed config changes.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenEvent.UpdateForm(updatedForm)"),
            "Settings route must send field edits through the settings controller.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenEvent.SaveChanges"),
            "Settings route must send save actions through the settings controller.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenEvent.DiscardChanges"),
            "Settings route must send discard actions through the settings controller.",
        )
        assertTrue(
            settingsSource.contains("controller.consumeEffects()"),
            "Settings route must consume one-shot controller effects after dispatch.",
        )
        assertTrue(
            settingsSource.contains("KeyboardType.Password"),
            "Secret settings fields must use password keyboard semantics, not only visual masking.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenState.from"),
            "Settings screen must derive Save/Discard availability from the tested settings screen state.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenAction.SaveChanges in state.availableActions"),
            "Save must be enabled only when the settings screen state allows it.",
        )
        assertTrue(
            settingsSource.contains("ProxySettingsScreenAction.DiscardChanges in state.availableActions"),
            "Discard must be enabled only when the settings screen state allows it.",
        )
    }

    @Test
    fun `cloudflare route renders dedicated tunnel management screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val cloudflareSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyCloudflareScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("CellularProxyCloudflareRoute("),
            "Cloudflare route must render the controller-backed tunnel management route instead of the stateless screen.",
        )
        assertTrue(
            !shellSource.contains("composable(Cloudflare.route) {\n            CellularProxyDestinationPlaceholder(Cloudflare)"),
            "Cloudflare route must not use the generic destination placeholder.",
        )
        assertTrue(
            cloudflareSource.contains("CloudflareScreenController("),
            "Cloudflare route must use the tested screen controller boundary.",
        )
        assertTrue(
            cloudflareSource.contains("configProvider: () -> AppConfig = AppConfig::default"),
            "Cloudflare route must accept an injectable config provider for app-shell runtime data.",
        )
        assertTrue(
            cloudflareSource.contains("tokenStatusProvider: () -> CloudflareTokenStatus") &&
                cloudflareSource.contains("redactionSecretsProvider: () -> LogRedactionSecrets"),
            "Cloudflare route must accept injectable token status and redaction providers.",
        )
        assertTrue(
            cloudflareSource.contains(
                "tunnelStatusProvider: () -> CloudflareTunnelStatus = { CloudflareTunnelStatus.disabled() }",
            ) &&
                cloudflareSource.contains("edgeSessionSummaryProvider: () -> String? = { null }") &&
                cloudflareSource.contains("managementApiRoundTripProvider: () -> String? = { null }"),
            "Cloudflare route must accept injectable runtime health providers.",
        )
        assertTrue(
            cloudflareSource.contains("configProvider = { currentConfigProvider() }") &&
                cloudflareSource.contains("tokenStatusProvider = { currentTokenStatusProvider() }") &&
                cloudflareSource.contains("tunnelStatusProvider = { currentTunnelStatusProvider() }") &&
                cloudflareSource.contains("edgeSessionSummaryProvider = { currentEdgeSessionSummaryProvider() }") &&
                cloudflareSource.contains(
                    "managementApiRoundTripProvider = { currentManagementApiRoundTripProvider() }",
                ),
            "Cloudflare route controller must be backed by injected Cloudflare state and health providers.",
        )
        assertTrue(
            cloudflareSource.contains("CloudflareScreenEvent.StartTunnel"),
            "Cloudflare route must dispatch lifecycle events through the controller.",
        )
        assertTrue(
            cloudflareSource.contains("CloudflareScreenEffect.CopyText"),
            "Cloudflare route must consume copy diagnostics effects from the controller.",
        )
        assertTrue(
            shellSource.contains("onCopyDiagnosticsText = onCopyText"),
            "Cloudflare route must send diagnostics copy effects to the app clipboard sink.",
        )
        assertTrue(
            shellSource.contains("configProvider = settingsInitialConfigProvider"),
            "Cloudflare app-shell route must read the persisted app config.",
        )
        assertTrue(
            shellSource.contains("cloudflareTokenStatusFrom(settingsLoadSensitiveConfig().cloudflareTunnelToken)") &&
                shellSource.contains("redactionSecretsProvider = logsAuditRedactionSecretsProvider"),
            "Cloudflare app-shell route must derive token status and full diagnostics redaction from sensitive storage.",
        )
        assertTrue(
            shellSource.contains("proxyStatusProvider: () -> ProxyServiceStatus") &&
                shellSource.contains("tunnelStatusProvider = { proxyStatusProvider().cloudflare }"),
            "Cloudflare app-shell route must derive tunnel lifecycle status from the shared proxy status provider.",
        )
        assertTrue(
            cloudflareSource.contains("val observedTunnelStatus = tunnelStatusProvider()") &&
                cloudflareSource.contains("val observedEdgeSessionSummary = edgeSessionSummaryProvider()") &&
                cloudflareSource.contains(
                    "LaunchedEffect(\n" +
                        "        observedConfig,\n" +
                        "        observedTokenStatus,\n" +
                        "        observedTunnelStatus,\n" +
                        "        observedEdgeSessionSummary,\n" +
                        "        observedManagementApiRoundTrip,\n" +
                        "        observedRedactionSecrets,\n" +
                        "    )",
                ),
            "Cloudflare route must refresh remembered controller state when live tunnel status or edge-session health changes.",
        )
        assertTrue(
            cloudflareSource.contains("val observedConfig = configProvider()") &&
                cloudflareSource.contains("val observedTokenStatus = tokenStatusProvider()") &&
                cloudflareSource.contains("val observedRedactionSecrets = redactionSecretsProvider()") &&
                cloudflareSource.contains(
                    "LaunchedEffect(\n" +
                        "        observedConfig,\n" +
                        "        observedTokenStatus,\n" +
                        "        observedTunnelStatus,\n" +
                        "        observedEdgeSessionSummary,\n" +
                        "        observedManagementApiRoundTrip,\n" +
                        "        observedRedactionSecrets,\n" +
                        "    )",
                ),
            "Cloudflare route must refresh remembered controller state when persisted config, token status, or redaction secrets change.",
        )
        assertTrue(
            cloudflareSource.contains("onStartTunnel: () -> Unit = {}"),
            "Cloudflare route must expose a callback for start tunnel actions.",
        )
        assertTrue(
            cloudflareSource.contains("onStopTunnel: () -> Unit = {}"),
            "Cloudflare route must expose a callback for stop tunnel actions.",
        )
        assertTrue(
            cloudflareSource.contains("onReconnectTunnel: () -> Unit = {}"),
            "Cloudflare route must expose a callback for reconnect tunnel actions.",
        )
        assertTrue(
            cloudflareSource.contains("onTestManagementTunnel: () -> Unit = {}"),
            "Cloudflare route must expose a callback for management tunnel test actions.",
        )
        assertTrue(
            cloudflareSource.contains("actionHandler = { action ->"),
            "Cloudflare route must bridge controller-dispatched actions to app-level callbacks.",
        )
        assertTrue(
            shellSource.contains("onStartTunnel = {"),
            "App shell must explicitly wire the Cloudflare start action callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onStopTunnel = {"),
            "App shell must explicitly wire the Cloudflare stop action callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onReconnectTunnel = {"),
            "App shell must explicitly wire the Cloudflare reconnect action callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onTestManagementTunnel = {"),
            "App shell must explicitly wire the Cloudflare management test action callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("LocalManagementApiAction.CloudflareStart") &&
                shellSource.contains("LocalManagementApiAction.CloudflareStop") &&
                shellSource.contains("LocalManagementApiAction.CloudflareReconnect") &&
                shellSource.contains("LocalManagementApiAction.CloudflareManagementStatus"),
            "App shell must dispatch Cloudflare lifecycle callbacks through the local Management API action dispatcher.",
        )

        listOf(
            "Tunnel enabled",
            "Tunnel token",
            "Tunnel lifecycle",
            "Management hostname",
            "Last connection error",
            "Edge sessions",
            "Management API round trip",
            "Start tunnel",
            "Stop tunnel",
            "Reconnect tunnel",
            "Test management tunnel",
            "Copy diagnostics",
        ).forEach { label ->
            assertTrue(
                cloudflareSource.contains(label),
                "Cloudflare screen must expose `$label`.",
            )
        }
        assertTrue(
            cloudflareSource.contains("actionsEnabled: Boolean = false"),
            "Cloudflare route actions must be disabled by default until runtime handlers are wired.",
        )
    }

    @Test
    fun `cloudflare screen state redacts unsafe connection error details`() {
        val state =
            CloudflareScreenState.from(
                config = AppConfig.default(),
                tunnelStatus =
                    CloudflareTunnelStatus.failed(
                        "Authorization: Bearer tunnel-secret\nhttps://example.com/api?token=tunnel-secret",
                    ),
            )

        assertFalse(
            state.lastConnectionError.contains("Bearer tunnel-secret"),
            "Cloudflare failure details must not expose authorization header values.",
        )
        assertFalse(
            state.lastConnectionError.contains("token=tunnel-secret"),
            "Cloudflare failure details must not expose URL query secrets.",
        )
        assertTrue(
            state.lastConnectionError.contains("[REDACTED]"),
            "Cloudflare failure details should preserve useful context with sensitive values redacted.",
        )
    }

    @Test
    fun `cloudflare screen state can represent invalid tunnel token status`() {
        val state =
            CloudflareScreenState.from(
                config = AppConfig.default(),
                tunnelStatus = CloudflareTunnelStatus.failed("invalid tunnel token"),
                tokenStatus = CloudflareTokenStatus.Invalid,
            )

        assertEquals(
            "Invalid",
            state.tokenStatus,
            "Cloudflare token status must distinguish invalid tokens from present tokens.",
        )
    }

    @Test
    fun `cloudflare screen state gates lifecycle actions by token and tunnel status`() {
        val defaultConfig = AppConfig.default()
        val enabledConfig =
            defaultConfig.copy(
                cloudflare =
                    defaultConfig.cloudflare.copy(
                        enabled = true,
                        tunnelTokenPresent = true,
                    ),
            )
        val stoppedActions = cloudflareActions(enabledConfig, CloudflareTunnelStatus.stopped())
        val degradedActions = cloudflareActions(enabledConfig, CloudflareTunnelStatus.degraded())
        val connectedActions = cloudflareActions(enabledConfig, CloudflareTunnelStatus.connected())
        val missingTokenActions =
            cloudflareActions(
                enabledConfig.copy(
                    cloudflare =
                        enabledConfig.cloudflare.copy(
                            tunnelTokenPresent = false,
                        ),
                ),
                CloudflareTunnelStatus.stopped(),
            )

        assertEquals(
            listOf(
                CloudflareScreenAction.StartTunnel,
                CloudflareScreenAction.CopyDiagnostics,
            ),
            stoppedActions,
        )
        assertEquals(
            listOf(
                CloudflareScreenAction.StopTunnel,
                CloudflareScreenAction.ReconnectTunnel,
                CloudflareScreenAction.CopyDiagnostics,
            ),
            degradedActions,
        )
        assertEquals(
            listOf(
                CloudflareScreenAction.StopTunnel,
                CloudflareScreenAction.TestManagementTunnel,
                CloudflareScreenAction.CopyDiagnostics,
            ),
            connectedActions,
        )
        assertEquals(
            listOf(CloudflareScreenAction.CopyDiagnostics),
            missingTokenActions,
        )
    }

    @Test
    fun `cloudflare high impact lifecycle actions are marked for confirmation`() {
        assertTrue(CloudflareScreenAction.StartTunnel.requiresConfirmation)
        assertTrue(CloudflareScreenAction.StopTunnel.requiresConfirmation)
        assertTrue(CloudflareScreenAction.ReconnectTunnel.requiresConfirmation)
        assertFalse(CloudflareScreenAction.TestManagementTunnel.requiresConfirmation)
        assertFalse(CloudflareScreenAction.CopyDiagnostics.requiresConfirmation)

        assertEquals(
            "Confirm Cloudflare tunnel start",
            CloudflareScreenAction.StartTunnel.confirmationTitle,
        )
        assertEquals(
            "Confirm Cloudflare tunnel stop",
            CloudflareScreenAction.StopTunnel.confirmationTitle,
        )
        assertEquals(
            "Confirm Cloudflare tunnel reconnect",
            CloudflareScreenAction.ReconnectTunnel.confirmationTitle,
        )
    }

    @Test
    fun `cloudflare high impact lifecycle actions require confirmation before dispatch`() {
        val cloudflareSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyCloudflareScreen.kt")
                .readText()

        assertEquals(
            CloudflareActionDispatchMode.ConfirmFirst,
            cloudflareActionDispatchMode(CloudflareScreenAction.StartTunnel),
        )
        assertEquals(
            CloudflareActionDispatchMode.ConfirmFirst,
            cloudflareActionDispatchMode(CloudflareScreenAction.StopTunnel),
        )
        assertEquals(
            CloudflareActionDispatchMode.ConfirmFirst,
            cloudflareActionDispatchMode(CloudflareScreenAction.ReconnectTunnel),
        )
        assertEquals(
            CloudflareActionDispatchMode.Immediate,
            cloudflareActionDispatchMode(CloudflareScreenAction.TestManagementTunnel),
        )
        assertEquals(
            CloudflareActionDispatchMode.Immediate,
            cloudflareActionDispatchMode(CloudflareScreenAction.CopyDiagnostics),
        )
        assertTrue(
            cloudflareSource.contains("AlertDialog"),
            "Cloudflare high-impact actions must show a confirmation dialog before invoking callbacks.",
        )
        assertTrue(
            cloudflareSource.contains("pendingConfirmationAction"),
            "Cloudflare screen must remember the action awaiting confirmation instead of dispatching it directly.",
        )
    }

    @Test
    fun `cloudflare confirmation dispatch rechecks current availability`() {
        assertTrue(
            cloudflareActionCanDispatch(
                action = CloudflareScreenAction.StopTunnel,
                actionsEnabled = true,
                availableActions = listOf(CloudflareScreenAction.StopTunnel),
            ),
        )
        assertFalse(
            cloudflareActionCanDispatch(
                action = CloudflareScreenAction.StopTunnel,
                actionsEnabled = false,
                availableActions = listOf(CloudflareScreenAction.StopTunnel),
            ),
        )
        assertFalse(
            cloudflareActionCanDispatch(
                action = CloudflareScreenAction.StopTunnel,
                actionsEnabled = true,
                availableActions = listOf(CloudflareScreenAction.StartTunnel),
            ),
        )
    }

    @Test
    fun `cloudflare screen state derives redacted copyable diagnostics`() {
        val state =
            CloudflareScreenState.from(
                config =
                    AppConfig.default().copy(
                        cloudflare =
                            AppConfig.default().cloudflare.copy(
                                enabled = true,
                                managementHostnameLabel = "https://proxy.example.test/manage?token=tunnel-secret",
                            ),
                    ),
                tunnelStatus =
                    CloudflareTunnelStatus.failed(
                        "Authorization: Bearer tunnel-secret\nhttps://example.test/api/status?token=tunnel-secret",
                    ),
                tokenStatus = CloudflareTokenStatus.Present,
                edgeSessionSummary = "2 sessions",
                managementApiRoundTrip = "HTTP 503 for token=tunnel-secret",
                secrets =
                    LogRedactionSecrets(
                        cloudflareTunnelToken = "tunnel-secret",
                    ),
            )

        val expectedDiagnostics =
            listOf(
                "Tunnel enabled: Enabled",
                "Tunnel token: Present",
                "Tunnel lifecycle: Failed",
                "Management hostname: https://proxy.example.test/manage?[REDACTED]",
                "Last connection error: Authorization: [REDACTED]\nhttps://example.test/api/status?[REDACTED]",
                "Edge sessions: 2 sessions",
                "Management API round trip: HTTP 503 for token=[REDACTED]",
            ).joinToString(separator = "\n")

        assertEquals(
            expectedDiagnostics,
            state.copyableDiagnostics,
            "Cloudflare diagnostics copy text must be derived from redacted screen fields.",
        )
        assertFalse(state.copyableDiagnostics.contains("tunnel-secret"))
    }

    @Test
    fun `rotation route renders dedicated rotation control screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val rotationSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyRotationScreen.kt")
                .readText()
        val rotationRefreshEffectKeys =
            rotationSource
                .substringAfter("LaunchedEffect(\n")
                .substringBefore("    ) {")

        assertTrue(
            shellSource.contains("CellularProxyRotationRoute("),
            "Rotation route must render the controller-backed root rotation route instead of the stateless screen.",
        )
        assertTrue(
            !shellSource.contains("composable(Rotation.route) {\n            CellularProxyDestinationPlaceholder(Rotation)"),
            "Rotation route must not use the generic destination placeholder.",
        )
        assertTrue(
            rotationSource.contains("RotationScreenController("),
            "Rotation route must use the tested screen controller boundary.",
        )
        assertTrue(
            rotationSource.contains("configProvider: () -> AppConfig = AppConfig::default") &&
                rotationSource.contains("redactionSecretsProvider: () -> LogRedactionSecrets"),
            "Rotation route must accept injectable config and redaction providers.",
        )
        assertTrue(
            rotationSource.contains("rootAvailabilityProvider: () -> RootAvailabilityStatus") &&
                rotationSource.contains("activeConnectionsProvider: () -> Long") &&
                rotationSource.contains("rotationStatusProvider: () -> RotationStatus"),
            "Rotation route must accept injectable live rotation, root availability, and active connection providers.",
        )
        assertTrue(
            rotationSource.contains("configProvider = { currentConfigProvider() }") &&
                rotationSource.contains("rotationStatusProvider = { currentRotationStatusProvider() }") &&
                rotationSource.contains("secretsProvider = { currentRedactionSecretsProvider() }") &&
                rotationSource.contains("rootAvailabilityProvider = { currentRootAvailabilityProvider() }") &&
                rotationSource.contains("activeConnectionsProvider = { currentActiveConnectionsProvider() }"),
            "Rotation route controller must be backed by injected Rotation state providers.",
        )
        assertTrue(
            rotationSource.contains("rememberUpdatedState(configProvider)") &&
                rotationSource.contains("currentConfigProvider()"),
            "Rotation route must preserve the latest config provider across recomposition.",
        )
        assertTrue(
            rotationSource.contains("LaunchedEffect(Unit)") &&
                rotationSource.contains("dispatchEvent(RotationScreenEvent.Refresh)"),
            "Rotation route must refresh provider-backed state when it enters composition.",
        )
        assertTrue(
            rotationSource.contains("val observedRotationStatus = rotationStatusProvider()") &&
                rotationSource.contains("val observedCurrentPublicIp = currentPublicIpProvider()") &&
                rotationSource.contains("val observedConfig = configProvider()") &&
                rotationSource.contains("val observedRootAvailability = rootAvailabilityProvider()") &&
                rotationSource.contains("val observedActiveConnections = activeConnectionsProvider()") &&
                rotationSource.contains("val observedRedactionSecrets = redactionSecretsProvider()") &&
                listOf(
                    "observedConfig",
                    "observedRotationStatus",
                    "observedCurrentPublicIp",
                    "observedRootAvailability",
                    "observedActiveConnections",
                    "observedRedactionSecrets",
                ).all(rotationRefreshEffectKeys::contains) &&
                rotationSource.contains("controller.handle(RotationScreenEvent.Refresh)"),
            "Rotation route must refresh remembered controller state when observed provider-backed state or redaction inputs change.",
        )
        assertTrue(
            rotationSource.contains("RotationScreenEvent.RotateMobileData"),
            "Rotation route must dispatch high-impact rotation events through the controller.",
        )
        assertTrue(
            rotationSource.contains("RotationScreenEffect.CopyText"),
            "Rotation route must consume copy diagnostics effects from the controller.",
        )
        assertTrue(
            shellSource.contains("onCopyRotationDiagnosticsText = onCopyText"),
            "Rotation route must send diagnostics copy effects to the app clipboard sink.",
        )
        assertTrue(
            shellSource.contains("configProvider = settingsInitialConfigProvider") &&
                shellSource.contains("redactionSecretsProvider = logsAuditRedactionSecretsProvider"),
            "Rotation app-shell route must read persisted app config and shared diagnostics redaction secrets.",
        )
        assertTrue(
            shellSource.contains("rootAvailabilityProvider = { proxyStatusProvider().rootAvailability }") &&
                shellSource.contains("activeConnectionsProvider = { proxyStatusProvider().metrics.activeConnections }") &&
                shellSource.contains("rotationStatusProvider = { rotationStatusProvider() }"),
            "Rotation app-shell route must derive live rotation, root availability, and active connection state from shared providers.",
        )
        assertTrue(
            rotationSource.contains("onCheckRoot: () -> Unit = {}"),
            "Rotation route must expose a callback for root checks.",
        )
        assertTrue(
            rotationSource.contains("onProbeCurrentPublicIp: () -> Unit = {}"),
            "Rotation route must expose a callback for current public IP probes.",
        )
        assertTrue(
            rotationSource.contains("onRotateMobileData: () -> Unit = {}"),
            "Rotation route must expose a callback for mobile-data rotation actions.",
        )
        assertTrue(
            rotationSource.contains("onRotateAirplaneMode: () -> Unit = {}"),
            "Rotation route must expose a callback for airplane-mode rotation actions.",
        )
        assertTrue(
            rotationSource.contains("actionHandler = { action ->"),
            "Rotation route must bridge controller-dispatched actions to app-level callbacks.",
        )
        assertTrue(
            shellSource.contains("onCheckRoot = {"),
            "App shell must explicitly wire the Rotation root-check callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onProbeCurrentPublicIp = {"),
            "App shell must explicitly wire the Rotation public-IP probe callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onRotateMobileData = {"),
            "App shell must explicitly wire the Rotation mobile-data callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("onRotateAirplaneMode = {"),
            "App shell must explicitly wire the Rotation airplane-mode callback, even before runtime dispatch exists.",
        )
        assertTrue(
            shellSource.contains("LocalManagementApiAction.RotateMobileData") &&
                shellSource.contains("LocalManagementApiAction.RotateAirplaneMode"),
            "App shell must dispatch high-impact rotation callbacks through the local Management API action dispatcher.",
        )
        assertTrue(
            shellSource.contains("LocalManagementApiAction.RootStatus") &&
                shellSource.contains("LocalManagementApiAction.PublicIp"),
            "App shell must dispatch Rotation root and current-IP probes through the local Management API action dispatcher.",
        )

        listOf(
            "Root availability",
            "Root operations",
            "Cooldown status",
            "Last rotation result",
            "Old public IP",
            "New public IP",
            "Current phase",
            "Pause/drain status",
            "Strict IP change",
            "Check root",
            "Probe current public IP",
            "Rotate mobile data",
            "Rotate airplane mode",
            "Copy diagnostics",
        ).forEach { label ->
            assertTrue(
                rotationSource.contains(label),
                "Rotation screen must expose `$label`.",
            )
        }
        assertTrue(
            rotationSource.contains("actionsEnabled: Boolean = false"),
            "Rotation route actions must be disabled by default until runtime handlers are wired.",
        )
    }

    @Test
    fun `rotation screen state summarizes active and failed rotation status`() {
        val activeState =
            RotationScreenState.from(
                config = AppConfig.default(),
                rotationStatus =
                    RotationStatus(
                        state = RotationState.DrainingConnections,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
                rootAvailability = RootAvailabilityStatus.Available,
                cooldownRemainingSeconds = 17,
                activeConnections = 2,
            )

        assertEquals("Available", activeState.rootAvailability)
        assertEquals("17 seconds remaining", activeState.cooldownStatus)
        assertEquals("In progress: MobileData", activeState.lastRotationResult)
        assertEquals("DrainingConnections", activeState.currentPhase)
        assertEquals("2 active connections draining", activeState.pauseDrainStatus)

        val failedState =
            RotationScreenState.from(
                config = AppConfig.default(),
                rotationStatus =
                    RotationStatus(
                        state = RotationState.Failed,
                        operation = RotationOperation.AirplaneMode,
                        failureReason = RotationFailureReason.RootUnavailable,
                    ),
                rootAvailability = RootAvailabilityStatus.Unavailable,
            )

        assertEquals("Failed: RootUnavailable", failedState.lastRotationResult)
        assertEquals("Unavailable", failedState.oldPublicIp)
        assertEquals("Unavailable", failedState.newPublicIp)
    }

    @Test
    fun `rotation action response parser extracts accepted rotation status`() {
        val status =
            rotationStatusFromManagementApiActionResponse(
                action = LocalManagementApiAction.RotateMobileData,
                response =
                    LocalManagementApiActionResponse(
                        statusCode = 202,
                        body =
                            "{" +
                                """"accepted":true,""" +
                                """"disposition":"accepted",""" +
                                """"rotation":{"state":"completed","operation":"mobile_data","oldPublicIp":"198.51.100.10","newPublicIp":"203.0.113.20","publicIpChanged":true,"failureReason":null}""" +
                                "}",
                    ),
            )

        assertEquals(
            RotationStatus(
                state = RotationState.Completed,
                operation = RotationOperation.MobileData,
                oldPublicIp = "198.51.100.10",
                newPublicIp = "203.0.113.20",
                publicIpChanged = true,
            ),
            status,
        )
    }

    @Test
    fun `public ip action response parser extracts observed public ip`() {
        assertEquals(
            PublicIpProbeActionResult.Observed("203.0.113.44"),
            publicIpFromManagementApiActionResponse(
                action = LocalManagementApiAction.PublicIp,
                response =
                    LocalManagementApiActionResponse(
                        statusCode = 200,
                        body = """{"publicIp":"203.0.113.44"}""",
                    ),
            ),
        )
        assertEquals(
            PublicIpProbeActionResult.Unavailable,
            publicIpFromManagementApiActionResponse(
                action = LocalManagementApiAction.PublicIp,
                response =
                    LocalManagementApiActionResponse(
                        statusCode = 200,
                        body = """{"publicIp":null}""",
                    ),
            ),
        )
        assertEquals(
            PublicIpProbeActionResult.NotPublicIpAction,
            publicIpFromManagementApiActionResponse(
                action = LocalManagementApiAction.RootStatus,
                response =
                    LocalManagementApiActionResponse(
                        statusCode = 200,
                        body = """{"publicIp":"203.0.113.44"}""",
                    ),
            ),
        )
    }

    @Test
    fun `current public ip display prefers latest status after refresh and explicit null probe clears cache`() {
        assertEquals(
            "198.51.100.10",
            currentPublicIpForRotationScreen(
                probedPublicIp = PublicIpProbeActionResult.NotPublicIpAction,
                statusPublicIp = "198.51.100.10",
            ),
        )
        assertEquals(
            "203.0.113.44",
            currentPublicIpForRotationScreen(
                probedPublicIp = PublicIpProbeActionResult.Observed("203.0.113.44"),
                statusPublicIp = "198.51.100.10",
            ),
        )
        assertEquals(
            "198.51.100.99",
            currentPublicIpForRotationScreen(
                probedPublicIp = PublicIpProbeActionResult.NotPublicIpAction,
                statusPublicIp = "198.51.100.99",
            ),
        )
        assertEquals(
            null,
            currentPublicIpForRotationScreen(
                probedPublicIp = PublicIpProbeActionResult.Unavailable,
                statusPublicIp = "198.51.100.99",
            ),
        )
    }

    @Test
    fun `rotation screen state derives redacted copyable diagnostics`() {
        val state =
            RotationScreenState.from(
                config =
                    AppConfig.default().copy(
                        root =
                            AppConfig.default().root.copy(
                                operationsEnabled = true,
                            ),
                    ),
                rotationStatus =
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "https://before.example.test/ip?token=rotation-secret",
                        newPublicIp = "Authorization: Bearer rotation-secret",
                        publicIpChanged = true,
                    ),
                rootAvailability = RootAvailabilityStatus.Available,
                cooldownRemainingSeconds = 0,
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "rotation-secret",
                    ),
            )

        assertEquals(
            listOf(
                "Root availability: Available",
                "Root operations: Enabled",
                "Cooldown status: Ready",
                "Last rotation result: Completed: MobileData",
                "Current public IP: Unavailable",
                "Old public IP: https://before.example.test/ip?[REDACTED]",
                "New public IP: Authorization: [REDACTED]",
                "Current phase: Completed",
                "Pause/drain status: Not active",
                "Strict IP change: Not required",
            ).joinToString(separator = "\n"),
            state.copyableDiagnostics,
            "Rotation diagnostics copy text must be derived from redacted screen fields.",
        )
        assertFalse(state.copyableDiagnostics.contains("rotation-secret"))
    }

    @Test
    fun `rotation screen action availability follows root cooldown and active rotation state`() {
        val enabledConfig =
            AppConfig.default().copy(
                root =
                    AppConfig.default().root.copy(
                        operationsEnabled = true,
                    ),
            )

        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.RotateMobileData,
                RotationScreenAction.RotateAirplaneMode,
                RotationScreenAction.CopyDiagnostics,
            ),
            rotationActions(
                config = enabledConfig,
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Available,
            ),
        )
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            rotationActions(
                config = enabledConfig,
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Available,
                cooldownRemainingSeconds = 3,
            ),
            "Rotation actions must be blocked during cooldown.",
        )
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            rotationActions(
                config = enabledConfig,
                rotationStatus =
                    RotationStatus(
                        state = RotationState.CheckingRoot,
                        operation = RotationOperation.MobileData,
                    ),
                rootAvailability = RootAvailabilityStatus.Available,
            ),
            "Rotation actions must be blocked while another rotation is active.",
        )
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            rotationActions(
                config = enabledConfig,
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Unavailable,
            ),
            "Rotation actions must be blocked when root is unavailable.",
        )
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            rotationActions(
                config = AppConfig.default(),
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Available,
            ),
            "Rotation actions must be blocked when root operations are disabled.",
        )
    }

    @Test
    fun `rotation high impact actions are marked for confirmation`() {
        assertFalse(RotationScreenAction.CheckRoot.requiresConfirmation)
        assertFalse(RotationScreenAction.ProbeCurrentPublicIp.requiresConfirmation)
        assertTrue(RotationScreenAction.RotateMobileData.requiresConfirmation)
        assertTrue(RotationScreenAction.RotateAirplaneMode.requiresConfirmation)
        assertFalse(RotationScreenAction.CopyDiagnostics.requiresConfirmation)

        assertEquals(
            "Confirm mobile data rotation",
            RotationScreenAction.RotateMobileData.confirmationTitle,
        )
        assertEquals(
            "Confirm airplane mode rotation",
            RotationScreenAction.RotateAirplaneMode.confirmationTitle,
        )
    }

    @Test
    fun `rotation high impact actions require confirmation before dispatch`() {
        val rotationSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyRotationScreen.kt")
                .readText()

        assertEquals(
            RotationActionDispatchMode.Immediate,
            rotationActionDispatchMode(RotationScreenAction.CheckRoot),
        )
        assertEquals(
            RotationActionDispatchMode.Immediate,
            rotationActionDispatchMode(RotationScreenAction.ProbeCurrentPublicIp),
        )
        assertEquals(
            RotationActionDispatchMode.ConfirmFirst,
            rotationActionDispatchMode(RotationScreenAction.RotateMobileData),
        )
        assertEquals(
            RotationActionDispatchMode.ConfirmFirst,
            rotationActionDispatchMode(RotationScreenAction.RotateAirplaneMode),
        )
        assertEquals(
            RotationActionDispatchMode.Immediate,
            rotationActionDispatchMode(RotationScreenAction.CopyDiagnostics),
        )
        assertTrue(
            rotationSource.contains("AlertDialog"),
            "Rotation high-impact actions must show a confirmation dialog before invoking callbacks.",
        )
        assertTrue(
            rotationSource.contains("pendingConfirmationAction"),
            "Rotation screen must remember the action awaiting confirmation instead of dispatching it directly.",
        )
    }

    @Test
    fun `rotation confirmation dispatch rechecks current availability`() {
        assertTrue(
            rotationActionCanDispatch(
                action = RotationScreenAction.RotateMobileData,
                actionsEnabled = true,
                availableActions = listOf(RotationScreenAction.RotateMobileData),
            ),
        )
        assertFalse(
            rotationActionCanDispatch(
                action = RotationScreenAction.RotateMobileData,
                actionsEnabled = false,
                availableActions = listOf(RotationScreenAction.RotateMobileData),
            ),
        )
        assertFalse(
            rotationActionCanDispatch(
                action = RotationScreenAction.RotateMobileData,
                actionsEnabled = true,
                availableActions = listOf(RotationScreenAction.CheckRoot),
            ),
        )
    }

    @Test
    fun `diagnostics route renders dedicated diagnostics screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val diagnosticsSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyDiagnosticsScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("CellularProxyDiagnosticsRoute("),
            "Diagnostics route must render the controller-backed diagnostics route instead of the stateless screen.",
        )
        assertTrue(
            !shellSource.contains("composable(Diagnostics.route) {\n            CellularProxyDestinationPlaceholder(Diagnostics)"),
            "Diagnostics route must not use the generic destination placeholder.",
        )
        assertTrue(
            diagnosticsSource.contains("DiagnosticsScreenController("),
            "Diagnostics route must use the tested screen controller boundary.",
        )
        assertTrue(
            diagnosticsSource.contains("DiagnosticsSuiteControllerFactory.create("),
            "Diagnostics route must use the provider-backed diagnostics suite factory by default.",
        )
        assertTrue(
            diagnosticsSource.contains("configProvider: () -> AppConfig") &&
                diagnosticsSource.contains("proxyStatusProvider: () -> ProxyServiceStatus") &&
                diagnosticsSource.contains("observedNetworksProvider: () -> List<NetworkDescriptor>") &&
                diagnosticsSource.contains("redactionSecretsProvider: () -> LogRedactionSecrets") &&
                diagnosticsSource.contains("localManagementApiProbeResultProvider: () -> LocalManagementApiProbeResult") &&
                diagnosticsSource.contains(
                    "cloudflareManagementApiProbeResultProvider: () -> CloudflareManagementApiProbeResult",
                ),
            "Diagnostics route must accept app-shell providers for config, live proxy status, observed networks, redaction secrets, and runtime probe results.",
        )
        assertTrue(
            diagnosticsSource.contains("config = { currentConfigProvider() }") &&
                diagnosticsSource.contains("proxyStatus = { currentProxyStatusProvider() }") &&
                diagnosticsSource.contains("observedNetworks = { currentObservedNetworksProvider() }") &&
                diagnosticsSource.contains(
                    "localManagementApiProbeResult = { currentLocalManagementApiProbeResultProvider() }",
                ) &&
                diagnosticsSource.contains(
                    "cloudflareManagementApiProbeResult = { currentCloudflareManagementApiProbeResultProvider() }",
                ) &&
                diagnosticsSource.contains("secretsProvider = { currentRedactionSecretsProvider() }"),
            "Diagnostics controller construction must use the latest injected providers instead of static defaults.",
        )
        assertTrue(
            shellSource.contains("configProvider = settingsInitialConfigProvider") &&
                shellSource.contains("proxyStatusProvider = proxyStatusProvider") &&
                shellSource.contains("observedNetworksProvider = observedNetworksProvider") &&
                shellSource.contains("redactionSecretsProvider = logsAuditRedactionSecretsProvider") &&
                shellSource.contains("localManagementApiProbeResultProvider = localManagementApiProbeResultProvider") &&
                shellSource.contains(
                    "cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider",
                ),
            "App shell must wire Diagnostics to persisted config, live status, redaction, and runtime probe providers.",
        )
        assertTrue(
            !diagnosticsSource.contains("DiagnosticsSuiteController(checks = emptyMap())"),
            "Diagnostics route must not hard-code an empty diagnostics suite that reports every check as missing.",
        )
        assertTrue(
            diagnosticsSource.contains("var screenState by remember { mutableStateOf(controller.state) }"),
            "Diagnostics route must mirror controller state into Compose state for recomposition.",
        )
        assertTrue(
            diagnosticsSource.contains("val eventMutex = remember { Mutex() }") &&
                diagnosticsSource.contains("eventMutex.withLock"),
            "Diagnostics route must serialize async controller event handling because the controller owns mutable state.",
        )
        assertTrue(
            diagnosticsSource.contains("DiagnosticsScreenEvent.RunAllChecks"),
            "Diagnostics route must dispatch run-all actions through the controller.",
        )
        assertTrue(
            diagnosticsSource.contains("DiagnosticsScreenEvent.RunCheck(type)"),
            "Diagnostics route must dispatch per-check actions through the controller.",
        )
        assertTrue(
            diagnosticsSource.contains("DiagnosticsScreenEffect.CopyText"),
            "Diagnostics route must consume copy summary effects from the controller.",
        )
        assertTrue(
            shellSource.contains("onCopyDiagnosticsSummaryText = onCopyText"),
            "Diagnostics route must send copy summary effects to the app clipboard sink.",
        )

        listOf(
            "Diagnostics",
            "Run all checks",
            "Copy summary",
            "Duration",
            "Error category",
            "Details",
        ).forEach { label ->
            assertTrue(
                diagnosticsSource.contains(label),
                "Diagnostics screen must expose `$label`.",
            )
        }
        assertEquals(
            DiagnosticCheckType.entries.map(DiagnosticCheckType::label),
            DiagnosticsScreenState.from(DiagnosticsResultModel.empty()).items.map(DiagnosticsScreenItem::label),
            "Diagnostics screen state must expose every registered diagnostic check label.",
        )
        assertTrue(
            diagnosticsSource.contains("actionsEnabled = true"),
            "Diagnostics route actions must be enabled through the controller-backed route.",
        )
    }

    @Test
    fun `diagnostics screen state summarizes status duration and failures`() {
        val state =
            DiagnosticsScreenState.from(
                model =
                    DiagnosticsResultModel.from(
                        completed =
                            listOf(
                                DiagnosticRunRecord(
                                    type = DiagnosticCheckType.RootAvailability,
                                    status = DiagnosticResultStatus.Passed,
                                    duration = 12.milliseconds,
                                    details = "Root shell available",
                                ),
                                DiagnosticRunRecord(
                                    type = DiagnosticCheckType.CloudflareManagementApi,
                                    status = DiagnosticResultStatus.Failed,
                                    duration = 85.milliseconds,
                                    errorCategory = "Authorization: Bearer secret-token",
                                    details = "https://example.test/status?token=secret-token",
                                ),
                            ),
                    ),
            )

        assertEquals("2 of 7 checks complete", state.completionSummary)
        assertEquals("failed", state.overallStatus)
        val rootItem = state.items.first { it.label == "Root availability" }
        val cloudflareManagementItem = state.items.first { it.label == "Cloudflare management API" }

        assertEquals("12 ms", rootItem.duration)
        assertEquals("Root shell available", rootItem.details)
        assertFalse(
            cloudflareManagementItem.errorCategory.contains("secret-token"),
            "Diagnostics failure category must stay redacted in UI state.",
        )
        assertFalse(
            cloudflareManagementItem.details.contains("token=secret-token"),
            "Diagnostics details must stay redacted in UI state.",
        )
    }

    @Test
    fun `diagnostics screen state redacts manually constructed result items`() {
        val state =
            DiagnosticsScreenState.from(
                model =
                    DiagnosticsResultModel(
                        results =
                            listOf(
                                DiagnosticResultItem(
                                    type = DiagnosticCheckType.LocalManagementApi,
                                    label = "Local management API",
                                    status = DiagnosticResultStatus.Failed,
                                    errorCategory = "Authorization: Bearer secret-token",
                                    details = "https://example.test/status?token=secret-token",
                                ),
                            ),
                        copyableSummary = "unsafe stale summary",
                    ),
            )
        val item = state.items.single()

        assertEquals("Authorization: [REDACTED]", item.errorCategory)
        assertEquals("https://example.test/status?[REDACTED]", item.details)
    }

    @Test
    fun `diagnostics screen state derives copyable summary from redacted items`() {
        val state =
            DiagnosticsScreenState.from(
                model =
                    DiagnosticsResultModel(
                        results =
                            listOf(
                                DiagnosticResultItem(
                                    type = DiagnosticCheckType.CloudflareManagementApi,
                                    label = "Cloudflare management API",
                                    status = DiagnosticResultStatus.Failed,
                                    durationMillis = 85,
                                    errorCategory = "Authorization: Bearer secret-token",
                                    details = "https://example.test/status?token=secret-token",
                                ),
                            ),
                        copyableSummary = "unsafe stale summary with secret-token",
                    ),
            )

        assertEquals(
            "Cloudflare management API: failed in 85ms (Authorization: [REDACTED]) - https://example.test/status?[REDACTED]",
            state.copyableSummary,
            "Diagnostics copy summary must be derived from redacted screen items, not the caller-provided model summary.",
        )
        assertFalse(state.copyableSummary.contains("secret-token"))
        assertFalse(state.copyableSummary.contains("unsafe stale summary"))
    }

    @Test
    fun `diagnostics screen state gates duplicate running checks and empty copy summary`() {
        val runningState =
            DiagnosticsScreenState.from(
                model = DiagnosticsResultModel.running(DiagnosticCheckType.RootAvailability),
            )
        val emptyState = DiagnosticsScreenState.from(DiagnosticsResultModel.empty())
        val completedState =
            DiagnosticsScreenState.from(
                model =
                    DiagnosticsResultModel.from(
                        completed =
                            listOf(
                                DiagnosticRunRecord(
                                    type = DiagnosticCheckType.RootAvailability,
                                    status = DiagnosticResultStatus.Passed,
                                    duration = 12.milliseconds,
                                    details = "Root shell available",
                                ),
                            ),
                    ),
            )

        assertFalse(
            DiagnosticsScreenAction.RunAllChecks in runningState.availableActions,
            "Run-all must be unavailable while any diagnostic check is already running.",
        )
        listOf(emptyState, runningState).forEach { state ->
            assertFalse(
                DiagnosticsScreenAction.CopySummary in state.availableActions,
                "Copy summary must be unavailable before there is a completed diagnostic result.",
            )
        }
        assertTrue(
            DiagnosticsScreenAction.CopySummary in completedState.availableActions,
            "Copy summary must be available once a redacted result exists.",
        )
        assertFalse(
            DiagnosticsScreenAction.RunCheck in
                runningState.items
                    .single { it.type == DiagnosticCheckType.RootAvailability }
                    .availableActions,
            "A running check must not expose its duplicate per-check run action.",
        )
        assertTrue(
            DiagnosticsScreenAction.RunCheck in
                runningState.items
                    .single { it.type == DiagnosticCheckType.SelectedRoute }
                    .availableActions,
            "Other checks should remain individually runnable while one check is running.",
        )

        val rerunningCompletedState =
            DiagnosticsScreenState.from(
                model =
                    DiagnosticsResultModel.from(
                        completed =
                            listOf(
                                DiagnosticRunRecord(
                                    type = DiagnosticCheckType.RootAvailability,
                                    status = DiagnosticResultStatus.Passed,
                                    duration = 12.milliseconds,
                                ),
                            ),
                        running = setOf(DiagnosticCheckType.RootAvailability),
                    ),
            )

        assertFalse(
            DiagnosticsScreenAction.RunAllChecks in rerunningCompletedState.availableActions,
            "Run-all must stay unavailable when a previously completed check is being rerun.",
        )
        assertEquals(
            DiagnosticResultStatus.Running.label,
            rerunningCompletedState.items
                .single { it.type == DiagnosticCheckType.RootAvailability }
                .status,
            "Rerunning a completed check must render as running instead of stale completed state.",
        )
        assertFalse(
            DiagnosticsScreenAction.RunCheck in
                rerunningCompletedState.items
                    .single { it.type == DiagnosticCheckType.RootAvailability }
                    .availableActions,
            "A rerunning completed check must not expose duplicate RunCheck.",
        )
    }

    @Test
    fun `logs audit route renders dedicated review screen`() {
        val shellSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyApp.kt")
                .readText()
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            shellSource.contains("CellularProxyLogsAuditRoute("),
            "Logs/Audit route must render through the controller-backed route.",
        )
        assertTrue(
            !shellSource.contains("composable(LogsAudit.route) {\n            CellularProxyDestinationPlaceholder(LogsAudit)"),
            "Logs/Audit route must not use the generic destination placeholder.",
        )

        listOf(
            "Logs/Audit",
            "Category",
            "Severity",
            "Time window",
            "Search",
            "Copy selected record",
            "Copy filtered summary",
            "Export redacted bundle",
            "No log or audit records match the current filters.",
        ).forEach { label ->
            assertTrue(
                logsAuditSource.contains(label),
                "Logs/Audit screen must expose `$label`.",
            )
        }
        assertTrue(
            logsAuditSource.contains("actionsEnabled: Boolean = false"),
            "Logs/Audit route actions must be disabled by default until runtime handlers are wired.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditScreenController("),
            "Logs/Audit route must own the tested screen controller.",
        )
        assertTrue(
            logsAuditSource.contains("val observedRows = logsAuditRowsProvider()") &&
                logsAuditSource.contains("val observedRedactionSecrets = redactionSecretsProvider()") &&
                logsAuditSource.contains("LaunchedEffect(") &&
                logsAuditSource.contains("observedRows") &&
                logsAuditSource.contains("observedRedactionSecrets") &&
                logsAuditSource.contains("LogsAuditScreenEvent.Refresh"),
            "Logs/Audit route must refresh remembered controller state when provider-backed rows or redaction secrets change.",
        )
        assertTrue(
            shellSource.contains("CellularProxyManagementAuditStore.managementApiAuditLog(context).readAll()") &&
                shellSource.contains("CellularProxyRootAuditStore.rootCommandAuditLog(context).readAll()") &&
                shellSource.contains("CellularProxyForegroundServiceAuditStore.foregroundServiceAuditLog(context).readAll()") &&
                shellSource.contains("CellularProxyLogsAuditStore.logsAuditLog(context).readAll()") &&
                shellSource.contains("logsAuditScreenRowsFromPersistedAuditRecords("),
            "App shell must load persisted Management API, root command, foreground-service, and generic log records for the Logs/Audit route.",
        )
        val serviceSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/service/CellularProxyForegroundService.kt")
                .readText()
        assertTrue(
            serviceSource.contains("CellularProxyForegroundServiceAuditStore.foregroundServiceAuditLog(this)") &&
                serviceSource.contains("::record") &&
                serviceSource.contains("recordAudit =") &&
                serviceSource.contains("reportAuditFailure ="),
            "Foreground service command audit records must be persisted through the app audit store.",
        )
        assertTrue(
            shellSource.contains("logsAuditRowsProvider = loadLogsAuditRows") &&
                logsAuditSource.contains("logsAuditRowsProvider: () -> List<LogsAuditScreenInputRow>") &&
                logsAuditSource.contains("rowsProvider = { currentRowsProvider() }") &&
                logsAuditSource.contains("exportSupported = true"),
            "Logs/Audit route must build its controller from persisted audit rows with export enabled.",
        )
        assertTrue(
            shellSource.contains("val loadLogsAuditRedactionSecrets: () -> LogRedactionSecrets") &&
                shellSource.contains("managementApiToken = sensitiveConfig.managementApiToken") &&
                shellSource.contains("proxyCredential = sensitiveConfig.proxyCredential.canonicalBasicPayload()") &&
                shellSource.contains("cloudflareTunnelToken = sensitiveConfig.cloudflareTunnelToken") &&
                shellSource.contains("logsAuditRedactionSecretsProvider = loadLogsAuditRedactionSecrets") &&
                shellSource.contains("redactionSecretsProvider = logsAuditRedactionSecretsProvider") &&
                logsAuditSource.contains("redactionSecretsProvider: () -> LogRedactionSecrets") &&
                logsAuditSource.contains("secretsProvider = { currentRedactionSecretsProvider() }"),
            "Logs/Audit route must pass sensitive-config-derived redaction secrets into persisted audit row rendering.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditScreenEvent.SelectRecord"),
            "Logs/Audit route must dispatch row-selection events through the controller.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditScreenEvent.UpdateFilter"),
            "Logs/Audit route must dispatch filter updates through the controller.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditScreenEffect.CopyText -> onCopyLogsAuditText(effect.text)"),
            "Logs/Audit route must forward copy effects to the app clipboard sink.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditScreenEffect.ExportBundle -> onExportLogsAuditBundle(effect.bundle)"),
            "Logs/Audit route must forward export effects to runtime export wiring.",
        )
        assertFalse(
            shellSource.contains("onExportLogsAuditBundle = {}"),
            "App shell must not drop Logs/Audit export effects at the top-level export boundary.",
        )
        assertTrue(
            shellSource.contains("shareLogsAuditExportBundle(context, exportBundle)"),
            "App shell must route Logs/Audit export effects to the Android share sheet.",
        )
        assertTrue(
            shellSource.contains("Intent.ACTION_SEND") &&
                shellSource.contains("setType(bundle.mediaType)") &&
                shellSource.contains("Intent.EXTRA_SUBJECT") &&
                shellSource.contains("Intent.EXTRA_TEXT") &&
                shellSource.contains("Intent.createChooser") &&
                shellSource.contains("context.startActivity("),
            "Logs/Audit export wiring must share the redacted text bundle through an Android ACTION_SEND intent.",
        )
    }

    @Test
    fun `logs audit screen state filters rows and redacts unsafe text`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "old-info",
                            category = LogsAuditScreenCategory.AppRuntime,
                            severity = LogsAuditScreenSeverity.Info,
                            occurredAtEpochMillis = 100,
                            title = "Runtime started",
                            detail = "Listening on loopback",
                        ),
                        LogsAuditScreenInputRow(
                            id = "failed-management",
                            category = LogsAuditScreenCategory.ManagementApi,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Management failed with plain-secret-token",
                            detail = "Authorization: Bearer secret-token\nhttps://example.test/api/status?token=secret-token",
                        ),
                    ),
                selectedRowId = "failed-management",
                secrets =
                    LogRedactionSecrets(
                        managementApiToken = "plain-secret-token",
                    ),
                filter =
                    LogsAuditScreenFilter(
                        category = LogsAuditScreenCategory.ManagementApi,
                        severity = LogsAuditScreenSeverity.Failed,
                        fromEpochMillis = 150,
                        toEpochMillis = 250,
                        search = "authorization",
                    ),
            )

        assertEquals("1 of 2 records", state.resultSummary)
        assertEquals("failed-management", state.selectedRow?.id)
        assertEquals(
            listOf(LogsAuditScreenAction.CopySelectedRecord, LogsAuditScreenAction.CopyFilteredSummary),
            state.availableActions,
        )
        val row = state.rows.single()
        assertFalse(
            row.title.contains("plain-secret-token"),
            "Logs/Audit row title must redact caller-provided sensitive tokens.",
        )
        assertFalse(
            row.detail.contains("secret-token"),
            "Logs/Audit row detail must redact query secrets.",
        )
    }

    @Test
    fun `logs audit screen exposes row selection callback`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains("onSelectRecord: (String) -> Unit = {}"),
            "Logs/Audit screen must expose a selected-record callback for row selection.",
        )
        assertTrue(
            logsAuditSource.contains("onSelectRecord(row.id)"),
            "Logs/Audit row controls must dispatch the selected row id.",
        )
    }

    @Test
    fun `logs audit screen exposes selected record feedback and clearing`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains("onClearSelection: () -> Unit = {}"),
            "Logs/Audit screen must expose a selection-clearing callback for controller wiring.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditSelectedRecord("),
            "Logs/Audit screen must render the currently selected record.",
        )
        assertTrue(
            logsAuditSource.contains("Selected record"),
            "Logs/Audit selected-record feedback must have an operator-facing label.",
        )
        assertTrue(
            logsAuditSource.contains("state.selectedRow"),
            "Logs/Audit selected-record feedback must derive from LogsAuditScreenState.",
        )
        assertTrue(
            logsAuditSource.contains("onClick = onClearSelection"),
            "Logs/Audit selected-record feedback must provide a clear-selection control.",
        )
        assertTrue(
            logsAuditSource.contains("Clear selection"),
            "Logs/Audit selected-record feedback must expose a clear-selection action.",
        )
    }

    @Test
    fun `logs audit copy and export callbacks dispatch redacted payloads`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains("onCopySelectedRecord: (String) -> Unit = {}"),
            "Copy selected record must hand the redacted selected-record payload to clipboard/runtime wiring.",
        )
        assertTrue(
            logsAuditSource.contains("onCopyFilteredSummary: (String) -> Unit = {}"),
            "Copy filtered summary must hand the redacted filtered summary payload to clipboard/runtime wiring.",
        )
        assertTrue(
            logsAuditSource.contains("onExportRedactedBundle: (LogsAuditScreenExportBundle) -> Unit = {}"),
            "Export must hand the typed redacted export bundle to runtime wiring.",
        )
        assertTrue(
            logsAuditSource.contains("state.copyableSelectedRecord?.let(onCopySelectedRecord)"),
            "Copy selected record must derive its payload from LogsAuditScreenState.",
        )
        assertTrue(
            logsAuditSource.contains("onCopyFilteredSummary(state.copyableFilteredSummary)"),
            "Copy filtered summary must derive its payload from LogsAuditScreenState.",
        )
        assertTrue(
            logsAuditSource.contains("state.exportBundle?.let(onExportRedactedBundle)"),
            "Export must derive its payload from LogsAuditScreenState.",
        )
    }

    @Test
    fun `logs audit screen exposes search filter update callback`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains("onUpdateFilter: (LogsAuditScreenFilter) -> Unit = {}"),
            "Logs/Audit screen must expose a filter-update callback for runtime/controller wiring.",
        )
        assertTrue(
            logsAuditSource.contains("label = { Text(\"Search logs\") }"),
            "Logs/Audit screen must render an editable search filter control.",
        )
        assertTrue(
            logsAuditSource.contains("value = state.searchDisplayText"),
            "Logs/Audit search input must display the redacted search text from screen state.",
        )
        assertTrue(
            !logsAuditSource.contains("value = state.filter.search"),
            "Logs/Audit search input must not echo raw search filter text.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(search = search))"),
            "Search edits must dispatch an updated LogsAuditScreenFilter instead of mutating local-only UI state.",
        )
        assertTrue(
            !logsAuditSource.contains("enabled = actionsEnabled,\n        label = { Text(\"Search logs\") }"),
            "Logs/Audit search editing must not be gated by copy/export action enablement.",
        )
    }

    @Test
    fun `logs audit screen exposes category and severity filter controls`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains("LogsAuditCategoryFilter("),
            "Logs/Audit screen must render interactive category filter controls.",
        )
        assertTrue(
            logsAuditSource.contains("LogsAuditSeverityFilter("),
            "Logs/Audit screen must render interactive severity filter controls.",
        )
        assertTrue(
            logsAuditSource.contains("FilterChip("),
            "Logs/Audit category and severity filters should use selectable filter chips.",
        )
        assertTrue(
            logsAuditSource.contains("Text(\"All categories\")"),
            "Category filtering must include an all-categories reset control.",
        )
        assertTrue(
            logsAuditSource.contains("Text(\"All severities\")"),
            "Severity filtering must include an all-severities reset control.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(category = category))"),
            "Category filter controls must dispatch a LogsAuditScreenFilter update.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(severity = severity))"),
            "Severity filter controls must dispatch a LogsAuditScreenFilter update.",
        )
        assertTrue(
            !logsAuditSource.contains("enabled = actionsEnabled,\n                label = { Text(\"All categories\") }"),
            "Logs/Audit category filtering must not be gated by copy/export action enablement.",
        )
        assertTrue(
            !logsAuditSource.contains("enabled = actionsEnabled,\n                label = { Text(\"All severities\") }"),
            "Logs/Audit severity filtering must not be gated by copy/export action enablement.",
        )
    }

    @Test
    fun `logs audit screen exposes time window filter controls`() {
        val logsAuditSource =
            repoRoot()
                .resolve("app/src/main/kotlin/com/cellularproxy/app/ui/CellularProxyLogsAuditScreen.kt")
                .readText()

        assertTrue(
            logsAuditSource.contains(
                "        LogsAuditTimeWindowFilter(\n" +
                    "            state = state,\n" +
                    "            onUpdateFilter = onUpdateFilter,\n" +
                    "        )",
            ),
            "Logs/Audit screen must render editable time-window filter controls.",
        )
        assertTrue(
            logsAuditSource.contains("label = { Text(\"From timestamp\") }"),
            "Logs/Audit screen must expose a lower-bound timestamp field.",
        )
        assertTrue(
            logsAuditSource.contains("label = { Text(\"To timestamp\") }"),
            "Logs/Audit screen must expose an upper-bound timestamp field.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(fromEpochMillis = fromEpochMillis))"),
            "From timestamp edits must dispatch a LogsAuditScreenFilter update.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(toEpochMillis = toEpochMillis))"),
            "To timestamp edits must dispatch a LogsAuditScreenFilter update.",
        )
        assertTrue(
            logsAuditSource.contains("onUpdateFilter(state.filter.copy(fromEpochMillis = null, toEpochMillis = null))"),
            "Time-window filtering must include an all-time reset control.",
        )
        assertTrue(
            !logsAuditSource.contains("enabled = actionsEnabled,\n        label = { Text(\"From timestamp\") }"),
            "Logs/Audit time-window filtering must not be gated by copy/export action enablement.",
        )
    }

    @Test
    fun `logs audit screen state redacts unsafe search display text`() {
        val state =
            LogsAuditScreenState.from(
                filter =
                    LogsAuditScreenFilter(
                        search = "Authorization: Bearer secret-token",
                    ),
            )

        assertEquals(
            "Authorization: [REDACTED]",
            state.searchDisplayText,
            "Logs/Audit filter summary must not echo raw search secrets.",
        )
    }

    @Test
    fun `logs audit screen state derives redacted copy summary and export bundle from filtered rows`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "included",
                            category = LogsAuditScreenCategory.CloudflareTunnel,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "Tunnel failed for secret-token",
                            detail = "Authorization: Bearer secret-token\nhttps://example.test/api/status?token=secret-token",
                        ),
                        LogsAuditScreenInputRow(
                            id = "excluded",
                            category = LogsAuditScreenCategory.AppRuntime,
                            severity = LogsAuditScreenSeverity.Info,
                            occurredAtEpochMillis = 100,
                            title = "Runtime started",
                            detail = "No issue",
                        ),
                    ),
                filter =
                    LogsAuditScreenFilter(
                        severity = LogsAuditScreenSeverity.Failed,
                    ),
                secrets =
                    LogRedactionSecrets(
                        cloudflareTunnelToken = "secret-token",
                    ),
                exportSupported = true,
                exportGeneratedAtEpochMillis = 300,
            )

        assertEquals(
            "Cloudflare tunnel | Failed | 200 | Tunnel failed for [REDACTED]\nAuthorization: [REDACTED]\nhttps://example.test/api/status?[REDACTED]",
            state.copyableFilteredSummary,
        )
        assertEquals(LogsAuditScreenAction.ExportRedactedBundle, state.availableActions.last())
        val exportBundle = assertNotNull(state.exportBundle)
        assertEquals("cellularproxy-logs-audit-300.txt", exportBundle.fileName)
        assertEquals("text/plain", exportBundle.mediaType)
        assertEquals(300, exportBundle.generatedAtEpochMillis)
        assertEquals(1, exportBundle.rowCount)
        assertTrue(exportBundle.text.contains("Rows: 1"))
        assertTrue(exportBundle.text.contains("Cloudflare tunnel | Failed | 200 | Tunnel failed for [REDACTED]"))
        assertFalse(exportBundle.text.contains("secret-token"))
        assertFalse(exportBundle.text.contains("Runtime started"))
    }

    @Test
    fun `logs audit screen state applies row limit after filtering and sorting`() {
        val state =
            LogsAuditScreenState.from(
                rows =
                    listOf(
                        LogsAuditScreenInputRow(
                            id = "newest-warning",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Warning,
                            occurredAtEpochMillis = 300,
                            title = "Proxy warning",
                            detail = "Excluded by severity",
                        ),
                        LogsAuditScreenInputRow(
                            id = "old-failed",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 100,
                            title = "Old failure",
                            detail = "Included but outside row limit",
                        ),
                        LogsAuditScreenInputRow(
                            id = "new-failed",
                            category = LogsAuditScreenCategory.ProxyServer,
                            severity = LogsAuditScreenSeverity.Failed,
                            occurredAtEpochMillis = 200,
                            title = "New failure",
                            detail = "Included by severity and limit",
                        ),
                    ),
                filter =
                    LogsAuditScreenFilter(
                        severity = LogsAuditScreenSeverity.Failed,
                    ),
                maxRows = 1,
            )

        assertEquals("1 of 3 records", state.resultSummary)
        assertEquals(listOf("new-failed"), state.rows.map(LogsAuditScreenRow::id))
        assertTrue(state.copyableFilteredSummary.contains("New failure"))
        assertFalse(
            state.copyableFilteredSummary.contains("Old failure"),
            "Rows outside the post-filter limit must not appear in copy summaries.",
        )
    }

    private fun repoRoot() = Path(requireNotNull(System.getProperty("user.dir"))).let { workingDirectory ->
        if (workingDirectory.resolve("settings.gradle.kts").toFile().exists()) {
            workingDirectory
        } else {
            assertNotNull(workingDirectory.parent)
        }
    }

    private fun cloudflareActions(
        config: AppConfig,
        tunnelStatus: CloudflareTunnelStatus,
    ): List<CloudflareScreenAction> {
        val state =
            CloudflareScreenState.from(
                config = config,
                tunnelStatus = tunnelStatus,
            )
        return state.availableActions
    }

    private fun rotationActions(
        config: AppConfig,
        rotationStatus: RotationStatus,
        rootAvailability: RootAvailabilityStatus,
        cooldownRemainingSeconds: Long? = null,
    ): List<RotationScreenAction> {
        val state =
            RotationScreenState.from(
                config = config,
                rotationStatus = rotationStatus,
                rootAvailability = rootAvailability,
                cooldownRemainingSeconds = cooldownRemainingSeconds,
            )
        return state.availableActions
    }
}
