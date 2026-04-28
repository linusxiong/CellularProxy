@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cellularproxy.app.audit.CellularProxyForegroundServiceAuditStore
import com.cellularproxy.app.audit.CellularProxyLogsAuditStore
import com.cellularproxy.app.audit.CellularProxyManagementAuditStore
import com.cellularproxy.app.audit.CellularProxyRootAuditStore
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SecureRandomSensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.config.SensitiveConfigRepository
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.network.AndroidNetworkRouteMonitor
import com.cellularproxy.app.service.CellularProxyForegroundService
import com.cellularproxy.app.service.ForegroundServiceActions
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionDispatcher
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.app.service.LocalManagementApiStatusReader
import com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Cloudflare
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Dashboard
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Diagnostics
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.LogsAudit
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Rotation
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Settings
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellularProxyApp() {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val plainConfigRepository = remember(context) { CellularProxyPlainConfigStore.repository(context) }
    val sensitiveRepository = remember(context) { SensitiveConfigRepositoryFactory.create(context) }
    val sensitiveConfigGenerator = remember { SecureRandomSensitiveConfigGenerator() }
    val localManagementApiActionDispatcher = remember { LocalManagementApiActionDispatcher() }
    val localManagementApiStatusReader = remember { LocalManagementApiStatusReader() }
    val networkRouteMonitor = remember(context) { AndroidNetworkRouteMonitor.create(context) }
    DisposableEffect(networkRouteMonitor) {
        onDispose {
            networkRouteMonitor.close()
        }
    }
    val dispatchForegroundServiceCommand: (String) -> Unit = { action ->
        context.startForegroundService(
            Intent(context, CellularProxyForegroundService::class.java).setAction(action),
        )
    }
    val loadSettingsConfig: () -> AppConfig = {
        runBlocking { plainConfigRepository.load() }
    }
    var proxyStatusState by remember {
        mutableStateOf(
            ProxyServiceStatus.stopped(configuredRoute = AppConfig.default().network.defaultRoutePolicy),
        )
    }
    var cloudflareManagementRoundTripState by remember { mutableStateOf<String?>(null) }
    var cloudflareManagementApiCheckState by remember {
        mutableStateOf(DashboardCloudflareManagementApiCheck.NotRun)
    }
    var rotationStatusState by remember { mutableStateOf(RotationStatus.idle()) }
    val saveSettingsConfig: (AppConfig) -> Unit = { config ->
        runBlocking { plainConfigRepository.save(config) }
    }
    val loadSensitiveConfig: () -> SensitiveConfig = {
        loadOrCreateSensitiveConfig(
            repository = sensitiveRepository,
            generator = sensitiveConfigGenerator,
        )
    }
    val refreshProxyStatus: suspend () -> Unit = {
        proxyStatusState =
            withContext(Dispatchers.IO) {
                val config = loadSettingsConfig()
                proxyStatusFromLiveStatusOrConfigFallback(
                    config = config,
                    liveStatus = {
                        localManagementApiStatusReader.load(
                            config = config,
                            sensitiveConfig = loadSensitiveConfig(),
                        )
                    },
                )
            }
    }
    LaunchedEffect(Unit) {
        refreshProxyStatus()
    }
    val loadProxyStatus: () -> ProxyServiceStatus = {
        proxyStatusState
    }
    val loadObservedNetworks: () -> List<NetworkDescriptor> = networkRouteMonitor::observedNetworks
    val loadLogsAuditRows: () -> List<LogsAuditScreenInputRow> = {
        logsAuditScreenRowsFromPersistedAuditRecords(
            managementRecords = CellularProxyManagementAuditStore.managementApiAuditLog(context).readAll(),
            rootRecords = CellularProxyRootAuditStore.rootCommandAuditLog(context).readAll(),
            foregroundServiceRecords =
                CellularProxyForegroundServiceAuditStore.foregroundServiceAuditLog(context).readAll(),
            genericRecords = CellularProxyLogsAuditStore.logsAuditLog(context).readAll(),
        )
    }
    val loadLogsAuditRedactionSecrets: () -> LogRedactionSecrets = {
        val sensitiveConfig = loadSensitiveConfig()
        LogRedactionSecrets(
            managementApiToken = sensitiveConfig.managementApiToken,
            proxyCredential = sensitiveConfig.proxyCredential.canonicalBasicPayload(),
            cloudflareTunnelToken = sensitiveConfig.cloudflareTunnelToken,
        )
    }
    val localManagementApiProbeResultProvider = {
        localManagementApiProbeResultFrom {
            localManagementApiActionDispatcher.dispatch(
                action = LocalManagementApiAction.RootStatus,
                config = loadSettingsConfig(),
                sensitiveConfig = loadSensitiveConfig(),
            )
        }
    }
    val cloudflareManagementApiProbeResultProvider = {
        val config = loadSettingsConfig()
        val sensitiveConfig = loadSensitiveConfig()
        val result =
            cloudflareManagementApiProbeResultFrom(
                config = config,
                tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
            ) {
                localManagementApiActionDispatcher.dispatch(
                    action = LocalManagementApiAction.CloudflareManagementStatus,
                    config = config,
                    sensitiveConfig = sensitiveConfig,
                )
            }
        cloudflareManagementApiCheckState = result.toDashboardCloudflareManagementApiCheck()
        result
    }
    val dispatchLocalManagementApiAction: (LocalManagementApiAction) -> Unit = { action ->
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    localManagementApiActionDispatcher.dispatch(
                        action = action,
                        config = loadSettingsConfig(),
                        sensitiveConfig = loadSensitiveConfig(),
                    )
                }
            }.onSuccess { response ->
                rotationStatusFromManagementApiActionResponse(
                    action = action,
                    response = response,
                )?.let { status ->
                    rotationStatusState = status
                }
                cloudflareManagementRoundTripSummary(
                    action = action,
                    response = response,
                )?.let { summary ->
                    cloudflareManagementRoundTripState = summary
                }
                if (action == LocalManagementApiAction.CloudflareManagementStatus) {
                    val config = loadSettingsConfig()
                    val sensitiveConfig = loadSensitiveConfig()
                    cloudflareManagementApiCheckState =
                        dashboardCloudflareManagementApiCheckFrom(
                            config = config,
                            tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
                        ) {
                            response
                        }
                }
                if (!response.isSuccessful) {
                    Log.w(
                        CELLULAR_PROXY_APP_TAG,
                        "Local management action $action returned HTTP ${response.statusCode}",
                    )
                }
            }.onFailure { throwable ->
                cloudflareManagementRoundTripFailureSummary(action)?.let { summary ->
                    cloudflareManagementRoundTripState = summary
                }
                if (action == LocalManagementApiAction.CloudflareManagementStatus) {
                    val config = loadSettingsConfig()
                    val sensitiveConfig = loadSensitiveConfig()
                    cloudflareManagementApiCheckState =
                        dashboardCloudflareManagementApiCheckFrom(
                            config = config,
                            tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
                        ) {
                            throw throwable
                        }
                }
                Log.w(CELLULAR_PROXY_APP_TAG, "Local management action $action failed", throwable)
            }
            refreshProxyStatus()
        }
    }

    MaterialTheme {
        BoxWithConstraints {
            val navigationChrome = cellularProxyNavigationChromeFor(maxWidth.value.toInt())
            val useNavigationRail = navigationChrome == CellularProxyNavigationChrome.NavigationRail

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text("CellularProxy")
                        },
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        CellularProxyNavigationBar(navController)
                    }
                },
            ) { contentPadding ->
                if (useNavigationRail) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    ) {
                        CellularProxyNavigationRail(navController)
                        CellularProxyNavigationHost(
                            navController = navController,
                            onStartProxyService = {
                                dispatchForegroundServiceCommand(ForegroundServiceActions.START_PROXY)
                            },
                            onStopProxyService = {
                                dispatchForegroundServiceCommand(ForegroundServiceActions.STOP_PROXY)
                            },
                            settingsInitialConfigProvider = loadSettingsConfig,
                            settingsSaveConfig = saveSettingsConfig,
                            settingsLoadSensitiveConfig = loadSensitiveConfig,
                            settingsSaveSensitiveConfig = sensitiveRepository::save,
                            logsAuditRowsProvider = loadLogsAuditRows,
                            logsAuditRedactionSecretsProvider = loadLogsAuditRedactionSecrets,
                            proxyStatusProvider = loadProxyStatus,
                            observedNetworksProvider = loadObservedNetworks,
                            cloudflareManagementRoundTripProvider = { cloudflareManagementRoundTripState },
                            latestCloudflareManagementApiCheck = cloudflareManagementApiCheckState,
                            localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                            cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                            onRefreshProxyStatus = {
                                coroutineScope.launch { refreshProxyStatus() }
                            },
                            dispatchLocalManagementApiAction = dispatchLocalManagementApiAction,
                            rotationStatusProvider = { rotationStatusState },
                            onCopyText = { endpointText ->
                                clipboard.setText(AnnotatedString(endpointText))
                            },
                            onExportLogsAuditBundle = { exportBundle ->
                                shareLogsAuditExportBundle(context, exportBundle)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    CellularProxyNavigationHost(
                        navController = navController,
                        onStartProxyService = {
                            dispatchForegroundServiceCommand(ForegroundServiceActions.START_PROXY)
                        },
                        onStopProxyService = {
                            dispatchForegroundServiceCommand(ForegroundServiceActions.STOP_PROXY)
                        },
                        settingsInitialConfigProvider = loadSettingsConfig,
                        settingsSaveConfig = saveSettingsConfig,
                        settingsLoadSensitiveConfig = loadSensitiveConfig,
                        settingsSaveSensitiveConfig = sensitiveRepository::save,
                        logsAuditRowsProvider = loadLogsAuditRows,
                        logsAuditRedactionSecretsProvider = loadLogsAuditRedactionSecrets,
                        proxyStatusProvider = loadProxyStatus,
                        observedNetworksProvider = loadObservedNetworks,
                        cloudflareManagementRoundTripProvider = { cloudflareManagementRoundTripState },
                        latestCloudflareManagementApiCheck = cloudflareManagementApiCheckState,
                        localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                        cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                        onRefreshProxyStatus = {
                            coroutineScope.launch { refreshProxyStatus() }
                        },
                        dispatchLocalManagementApiAction = dispatchLocalManagementApiAction,
                        rotationStatusProvider = { rotationStatusState },
                        onCopyText = { endpointText ->
                            clipboard.setText(AnnotatedString(endpointText))
                        },
                        onExportLogsAuditBundle = { exportBundle ->
                            shareLogsAuditExportBundle(context, exportBundle)
                        },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    )
                }
            }
        }
    }
}

private const val CELLULAR_PROXY_APP_TAG = "CellularProxyApp"

private fun shareLogsAuditExportBundle(
    context: Context,
    bundle: LogsAuditScreenExportBundle,
) {
    val sendIntent =
        Intent(Intent.ACTION_SEND)
            .setType(bundle.mediaType)
            .putExtra(Intent.EXTRA_SUBJECT, bundle.fileName)
            .putExtra(Intent.EXTRA_TEXT, bundle.text)
    context.startActivity(Intent.createChooser(sendIntent, "Export Logs/Audit bundle"))
}

internal enum class CellularProxyNavigationChrome {
    BottomBar,
    NavigationRail,
}

internal fun cellularProxyNavigationChromeFor(availableWidthDp: Int) = if (availableWidthDp >= 600) {
    CellularProxyNavigationChrome.NavigationRail
} else {
    CellularProxyNavigationChrome.BottomBar
}

internal fun proxyStatusFromLiveStatusOrConfigFallback(
    config: AppConfig,
    liveStatus: () -> ProxyServiceStatus?,
): ProxyServiceStatus = runCatching(liveStatus).getOrNull()
    ?: ProxyServiceStatus.stopped(configuredRoute = config.network.defaultRoutePolicy)

internal fun cloudflareManagementRoundTripSummary(
    action: LocalManagementApiAction,
    response: LocalManagementApiActionResponse,
): String? = when (action) {
    LocalManagementApiAction.CloudflareManagementStatus -> "HTTP ${response.statusCode}"
    else -> null
}

internal fun cloudflareManagementRoundTripFailureSummary(action: LocalManagementApiAction): String? = when (action) {
    LocalManagementApiAction.CloudflareManagementStatus -> "Request failed"
    else -> null
}

internal fun CloudflareManagementApiProbeResult.toDashboardCloudflareManagementApiCheck(): DashboardCloudflareManagementApiCheck = when (this) {
    CloudflareManagementApiProbeResult.NotConfigured -> DashboardCloudflareManagementApiCheck.NotRun
    CloudflareManagementApiProbeResult.Authenticated -> DashboardCloudflareManagementApiCheck.Passed
    CloudflareManagementApiProbeResult.Unavailable,
    CloudflareManagementApiProbeResult.Unauthorized,
    CloudflareManagementApiProbeResult.Error,
    -> DashboardCloudflareManagementApiCheck.Failed
}

internal fun dashboardCloudflareManagementApiCheckFrom(
    config: AppConfig,
    tunnelTokenPresent: Boolean,
    request: () -> LocalManagementApiActionResponse,
): DashboardCloudflareManagementApiCheck = cloudflareManagementApiProbeResultFrom(
    config = config,
    tunnelTokenPresent = tunnelTokenPresent,
    request = request,
).toDashboardCloudflareManagementApiCheck()

internal fun rotationStatusFromManagementApiActionResponse(
    action: LocalManagementApiAction,
    response: LocalManagementApiActionResponse,
): RotationStatus? {
    if (action != LocalManagementApiAction.RotateMobileData && action != LocalManagementApiAction.RotateAirplaneMode) {
        return null
    }
    return runCatching {
        Json
            .parseToJsonElement(response.body)
            .jsonObject
            .objectValue("rotation")
            .toRotationStatus()
    }.getOrNull()
}

private fun JsonObject.toRotationStatus(): RotationStatus = RotationStatus(
    state = stringValue("state").toRotationState(),
    operation = nullableStringValue("operation")?.toRotationOperation(),
    oldPublicIp = nullableStringValue("oldPublicIp"),
    newPublicIp = nullableStringValue("newPublicIp"),
    publicIpChanged = nullableBooleanValue("publicIpChanged"),
    failureReason = nullableStringValue("failureReason")?.toRotationFailureReason(),
)

private fun String.toRotationState(): RotationState = when (this) {
    "idle" -> RotationState.Idle
    "checking_cooldown" -> RotationState.CheckingCooldown
    "checking_root" -> RotationState.CheckingRoot
    "probing_old_public_ip" -> RotationState.ProbingOldPublicIp
    "pausing_new_requests" -> RotationState.PausingNewRequests
    "draining_connections" -> RotationState.DrainingConnections
    "running_disable_command" -> RotationState.RunningDisableCommand
    "waiting_for_toggle_delay" -> RotationState.WaitingForToggleDelay
    "running_enable_command" -> RotationState.RunningEnableCommand
    "waiting_for_network_return" -> RotationState.WaitingForNetworkReturn
    "probing_new_public_ip" -> RotationState.ProbingNewPublicIp
    "resuming_proxy_requests" -> RotationState.ResumingProxyRequests
    "completed" -> RotationState.Completed
    "failed" -> RotationState.Failed
    else -> error("Unknown rotation state: $this")
}

private fun String.toRotationOperation(): RotationOperation = when (this) {
    "mobile_data" -> RotationOperation.MobileData
    "airplane_mode" -> RotationOperation.AirplaneMode
    else -> error("Unknown rotation operation: $this")
}

private fun String.toRotationFailureReason(): RotationFailureReason = when (this) {
    "cooldown_active" -> RotationFailureReason.CooldownActive
    "root_unavailable" -> RotationFailureReason.RootUnavailable
    "old_public_ip_probe_failed" -> RotationFailureReason.OldPublicIpProbeFailed
    "root_command_failed" -> RotationFailureReason.RootCommandFailed
    "root_command_timed_out" -> RotationFailureReason.RootCommandTimedOut
    "network_return_timed_out" -> RotationFailureReason.NetworkReturnTimedOut
    "new_public_ip_probe_failed" -> RotationFailureReason.NewPublicIpProbeFailed
    "strict_ip_change_required" -> RotationFailureReason.StrictIpChangeRequired
    "root_operations_disabled" -> RotationFailureReason.RootOperationsDisabled
    "execution_unavailable" -> RotationFailureReason.ExecutionUnavailable
    else -> error("Unknown rotation failure reason: $this")
}

private fun JsonObject.objectValue(name: String): JsonObject = requireNotNull(nullableElementValue(name)?.jsonObject)

private fun JsonObject.stringValue(name: String): String = requireNotNull(nullableStringValue(name))

private fun JsonObject.nullableStringValue(name: String): String? = nullableElementValue(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.jsonPrimitive
    ?.content

private fun JsonObject.nullableBooleanValue(name: String): Boolean? = nullableElementValue(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.jsonPrimitive
    ?.booleanOrNull

private fun JsonObject.nullableElementValue(name: String): JsonElement? = this[name]

private val JsonElement.isJsonNull: Boolean
    get() = this is JsonNull

@Composable
private fun CellularProxyNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        CellularProxyNavigationDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                },
                label = {
                    Text(destination.label)
                },
                icon = {
                    Icon(destination.icon, contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun CellularProxyNavigationRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationRail {
        CellularProxyNavigationDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                },
                label = {
                    Text(destination.label)
                },
                icon = {
                    Icon(destination.icon, contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun CellularProxyNavigationHost(
    navController: NavHostController,
    onStartProxyService: () -> Unit,
    onStopProxyService: () -> Unit,
    settingsInitialConfigProvider: () -> AppConfig,
    settingsSaveConfig: (AppConfig) -> Unit,
    settingsLoadSensitiveConfig: () -> SensitiveConfig,
    settingsSaveSensitiveConfig: (SensitiveConfig) -> Unit,
    logsAuditRowsProvider: () -> List<LogsAuditScreenInputRow>,
    logsAuditRedactionSecretsProvider: () -> LogRedactionSecrets,
    proxyStatusProvider: () -> ProxyServiceStatus,
    observedNetworksProvider: () -> List<NetworkDescriptor>,
    cloudflareManagementRoundTripProvider: () -> String?,
    latestCloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck,
    localManagementApiProbeResultProvider: () -> LocalManagementApiProbeResult,
    cloudflareManagementApiProbeResultProvider: () -> CloudflareManagementApiProbeResult,
    onRefreshProxyStatus: () -> Unit,
    dispatchLocalManagementApiAction: (LocalManagementApiAction) -> Unit,
    rotationStatusProvider: () -> RotationStatus,
    onCopyText: (String) -> Unit,
    onExportLogsAuditBundle: (LogsAuditScreenExportBundle) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = CellularProxyNavigationDestination.Dashboard.route,
        modifier = modifier,
    ) {
        composable(Dashboard.route) {
            CellularProxyDashboardRoute(
                statusProvider = {
                    val config = settingsInitialConfigProvider()
                    DashboardStatusModel.from(
                        config = config,
                        status = proxyStatusProvider(),
                        recentLogs = dashboardLogSummariesFromLogsAuditRows(logsAuditRowsProvider()),
                        redactionSecrets = logsAuditRedactionSecretsProvider(),
                        latestCloudflareManagementApiCheck = latestCloudflareManagementApiCheck,
                        managementApiTokenPresent = settingsLoadSensitiveConfig().managementApiToken.isNotBlank(),
                    )
                },
                onStartProxyService = onStartProxyService,
                onStopProxyService = onStopProxyService,
                onRefreshStatus = onRefreshProxyStatus,
                onOpenRiskDetails = { navController.navigate(LogsAudit.route) },
                onOpenCloudflare = { navController.navigate(Cloudflare.route) },
                onOpenRotation = { navController.navigate(Rotation.route) },
                onOpenLogs = { navController.navigate(LogsAudit.route) },
                onOpenDiagnostics = { navController.navigate(Diagnostics.route) },
                onCopyProxyEndpointText = onCopyText,
            )
        }
        composable(Settings.route) {
            CellularProxySettingsRoute(
                initialConfigProvider = settingsInitialConfigProvider,
                saveConfig = settingsSaveConfig,
                loadSensitiveConfig = settingsLoadSensitiveConfig,
                saveSensitiveConfig = settingsSaveSensitiveConfig,
            )
        }
        composable(Cloudflare.route) {
            CellularProxyCloudflareRoute(
                configProvider = settingsInitialConfigProvider,
                tokenStatusProvider = {
                    cloudflareTokenStatusFrom(settingsLoadSensitiveConfig().cloudflareTunnelToken)
                },
                tunnelStatusProvider = { proxyStatusProvider().cloudflare },
                managementApiRoundTripProvider = cloudflareManagementRoundTripProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                onStartTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareStart)
                },
                onStopTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareStop)
                },
                onReconnectTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareReconnect)
                },
                onTestManagementTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareManagementStatus)
                },
                onCopyDiagnosticsText = onCopyText,
            )
        }
        composable(Rotation.route) {
            CellularProxyRotationRoute(
                configProvider = settingsInitialConfigProvider,
                rotationStatusProvider = { rotationStatusProvider() },
                rootAvailabilityProvider = { proxyStatusProvider().rootAvailability },
                activeConnectionsProvider = { proxyStatusProvider().metrics.activeConnections },
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                onCheckRoot = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.RootStatus)
                },
                onProbeCurrentPublicIp = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.PublicIp)
                },
                onRotateMobileData = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.RotateMobileData)
                },
                onRotateAirplaneMode = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.RotateAirplaneMode)
                },
                onCopyRotationDiagnosticsText = onCopyText,
            )
        }
        composable(Diagnostics.route) {
            CellularProxyDiagnosticsRoute(
                configProvider = settingsInitialConfigProvider,
                proxyStatusProvider = proxyStatusProvider,
                observedNetworksProvider = observedNetworksProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                onCopyDiagnosticsSummaryText = onCopyText,
            )
        }
        composable(LogsAudit.route) {
            CellularProxyLogsAuditRoute(
                logsAuditRowsProvider = logsAuditRowsProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                onCopyLogsAuditText = onCopyText,
                onExportLogsAuditBundle = onExportLogsAuditBundle,
            )
        }
    }
}

private fun loadOrCreateSensitiveConfig(
    repository: SensitiveConfigRepository,
    generator: SensitiveConfigGenerator,
): SensitiveConfig = when (val result = repository.load()) {
    is SensitiveConfigLoadResult.Loaded -> result.config
    SensitiveConfigLoadResult.MissingRequiredSecrets ->
        generator
            .generateDefaultSensitiveConfig()
            .also(repository::save)
    is SensitiveConfigLoadResult.Invalid -> error("Sensitive config is invalid: ${result.reason}")
}

@Composable
private fun CellularProxyDestinationPlaceholder(destination: CellularProxyNavigationDestination) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = destination.label,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "${destination.label} console will be wired here.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
