@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SecureRandomSensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.config.SensitiveConfigRepository
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import com.cellularproxy.app.diagnostics.LocalManagementApiProbeResult
import com.cellularproxy.app.diagnostics.PublicIpDiagnosticsProbeResult
import com.cellularproxy.app.network.AndroidBoundNetworkSocketConnector
import com.cellularproxy.app.network.AndroidNetworkRouteMonitor
import com.cellularproxy.app.service.CellularProxyForegroundService
import com.cellularproxy.app.service.ForegroundServiceActions
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionDispatcher
import com.cellularproxy.app.service.LocalManagementApiActionResponse
import com.cellularproxy.app.service.LocalManagementApiStatusReader
import com.cellularproxy.app.service.defaultPublicIpProbeEndpoint
import com.cellularproxy.app.status.DashboardCloudflareManagementApiCheck
import com.cellularproxy.app.status.DashboardRecentTrafficSampler
import com.cellularproxy.app.status.DashboardStatusModel
import com.cellularproxy.app.status.DashboardTrafficSummary
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Cloudflare
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Dashboard
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Diagnostics
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.LogsAudit
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Rotation
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Settings
import com.cellularproxy.network.PublicIpProbeResult
import com.cellularproxy.network.RouteBoundPublicIpProbe
import com.cellularproxy.network.RouteBoundSocketProvider
import com.cellularproxy.root.BlockingRootCommandProcessExecutor
import com.cellularproxy.root.RootAvailabilityChecker
import com.cellularproxy.root.RootCommandExecutor
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.Locale

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
    val rootAvailabilityChecker =
        remember(context) {
            RootAvailabilityChecker(
                RootCommandExecutor(
                    processExecutor = BlockingRootCommandProcessExecutor(),
                    recordAudit = CellularProxyRootAuditStore.rootCommandAuditLog(context)::record,
                ),
            )
        }
    val networkRouteMonitor = remember(context) { AndroidNetworkRouteMonitor.create(context) }
    DisposableEffect(networkRouteMonitor) {
        onDispose {
            networkRouteMonitor.close()
        }
    }
    val publicIpProbe =
        remember(context, networkRouteMonitor) {
            RouteBoundPublicIpProbe(
                RouteBoundSocketProvider(
                    observedNetworks = networkRouteMonitor::observedNetworks,
                    connector = AndroidBoundNetworkSocketConnector.create(context),
                ),
            )
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
    var cloudflareEdgeSessionSummaryState by remember { mutableStateOf<String?>(null) }
    var cloudflareManagementRoundTripState by remember { mutableStateOf<String?>(null) }
    var cloudflareManagementRoundTripVersion by remember { mutableLongStateOf(0L) }
    var cloudflareActionCompletionVersion by remember { mutableLongStateOf(0L) }
    var cloudflareManagementApiCheckState by remember {
        mutableStateOf(DashboardCloudflareManagementApiCheck.NotRun)
    }
    var dashboardLifecycleActionCompletionVersion by remember { mutableLongStateOf(0L) }
    var rotationStatusState by remember { mutableStateOf(RotationStatus.idle()) }
    var rotationCooldownRemainingSecondsState by remember { mutableStateOf<Long?>(null) }
    var explicitRootAvailabilityState by remember { mutableStateOf<RootAvailabilityStatus?>(null) }
    var rotationActionCompletionVersion by remember { mutableLongStateOf(0L) }
    var currentPublicIpState by remember {
        mutableStateOf<PublicIpProbeActionResult>(PublicIpProbeActionResult.NotPublicIpAction)
    }
    var observedNetworksState by remember {
        mutableStateOf(networkRouteMonitor.observedNetworks())
    }
    var selectedLanguageState by remember(context) {
        mutableStateOf(context.loadSettingsLanguageOption())
    }
    var recentTrafficState by remember { mutableStateOf<DashboardTrafficSummary?>(null) }
    val recentTrafficSampler =
        remember {
            DashboardRecentTrafficSampler(
                windowMillis = 60_000L,
                nowElapsedMillis = SystemClock::elapsedRealtime,
            )
        }
    val loadSensitiveConfigResult: () -> SensitiveConfigLoadResult = {
        sensitiveRepository.load()
    }
    val loadSensitiveConfig: () -> SensitiveConfig = {
        loadOrCreateSensitiveConfig(
            repository = sensitiveRepository,
            generator = sensitiveConfigGenerator,
        )
    }
    val refreshProxyStatus: suspend () -> Unit = {
        var refreshedRotationStatus: RotationStatus? = null
        var refreshedRotationCooldownRemainingSeconds: Long? = null
        var refreshedCloudflareEdgeSessionSummary: String? = null
        val refreshedProxyStatus =
            withContext(Dispatchers.IO) {
                val config = loadSettingsConfig()
                val sensitiveConfig =
                    sensitiveConfigFromLoadResultOrCreateDefault(loadSensitiveConfigResult()) {
                        sensitiveConfigGenerator
                            .generateDefaultSensitiveConfig()
                            .also(sensitiveRepository::save)
                    }
                sensitiveConfig?.let { loadedSensitiveConfig ->
                    localManagementApiStatusReader
                        .loadSnapshot(
                            config = config,
                            sensitiveConfig = loadedSensitiveConfig,
                        )?.also { snapshot ->
                            refreshedRotationStatus = snapshot.rotationStatus
                            refreshedRotationCooldownRemainingSeconds = snapshot.rotationCooldownRemainingSeconds
                            refreshedCloudflareEdgeSessionSummary = snapshot.cloudflareEdgeSessionSummary
                        }?.proxyStatus
                } ?: ProxyServiceStatus.stopped(configuredRoute = config.network.defaultRoutePolicy)
            }
        proxyStatusState =
            proxyStatusWithExplicitRootAvailability(
                refreshedStatus = refreshedProxyStatus,
                explicitRootAvailability = explicitRootAvailabilityState,
            )
        rotationCooldownRemainingSecondsState = refreshedRotationCooldownRemainingSeconds
        cloudflareEdgeSessionSummaryState = refreshedCloudflareEdgeSessionSummary
        recentTrafficState = recentTrafficSampler.observe(refreshedProxyStatus.metrics)
        refreshedRotationStatus?.let { rotationStatus ->
            rotationStatusState = rotationStatus
        }
    }
    val saveSettingsConfig: (AppConfig) -> Unit = { config ->
        val restartRunningProxy = shouldRestartProxyAfterSettingsSave(proxyStatusState)
        runBlocking { plainConfigRepository.save(config) }
        if (restartRunningProxy) {
            dispatchForegroundServiceCommand(ForegroundServiceActions.STOP_PROXY)
            coroutineScope.launch {
                delay(DASHBOARD_SERVICE_COMMAND_RESTART_DELAY_MILLIS)
                dispatchForegroundServiceCommand(ForegroundServiceActions.START_PROXY)
                delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                refreshProxyStatus()
            }
        } else {
            coroutineScope.launch {
                refreshProxyStatus()
            }
        }
    }
    LaunchedEffect(Unit) {
        refreshProxyStatus()
    }
    LaunchedEffect(networkRouteMonitor) {
        while (true) {
            observedNetworksState = networkRouteMonitor.observedNetworks()
            delay(OBSERVED_NETWORKS_REFRESH_INTERVAL_MILLIS)
        }
    }
    val loadProxyStatus: () -> ProxyServiceStatus = {
        proxyStatusState
    }
    val loadObservedNetworks: () -> List<NetworkDescriptor> = { observedNetworksState }
    val loadLogsAuditRedactionSecrets: () -> LogRedactionSecrets = {
        logRedactionSecretsFromSensitiveConfigLoadResult(loadSensitiveConfigResult())
    }
    val loadLogsAuditRows: () -> List<LogsAuditScreenInputRow> = {
        logsAuditScreenRowsFromPersistedAuditRecords(
            managementRecords = CellularProxyManagementAuditStore.managementApiAuditLog(context).readAll(),
            rootRecords = CellularProxyRootAuditStore.rootCommandAuditLog(context).readAll(),
            foregroundServiceRecords =
                CellularProxyForegroundServiceAuditStore.foregroundServiceAuditLog(context).readAll(),
            genericRecords = CellularProxyLogsAuditStore.logsAuditLog(context, loadLogsAuditRedactionSecrets).readAll(),
        )
    }
    val localManagementApiProbeResultProvider = {
        val config = loadSettingsConfig()
        localManagementApiProbeResultFromSensitiveConfigLoadResult(loadSensitiveConfigResult()) { sensitiveConfig ->
            localManagementApiActionDispatcher.dispatch(
                action = LocalManagementApiAction.RootStatus,
                config = config,
                sensitiveConfig = sensitiveConfig,
            )
        }
    }
    val publicIpDiagnosticsProbeResultProvider = {
        publicIpDiagnosticsProbeResultFromPublicIpProbeResult(
            runBlocking {
                publicIpProbe.probe(
                    route = publicIpProbeRouteForRotation(),
                    endpoint = defaultPublicIpProbeEndpoint(),
                )
            },
        )
    }
    val cloudflareManagementApiProbeResultProvider = {
        val config = loadSettingsConfig()
        val sensitiveConfigResult = loadSensitiveConfigResult()
        val result =
            cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
                config = config,
                result = sensitiveConfigResult,
            ) { sensitiveConfig ->
                localManagementApiActionDispatcher.dispatch(
                    action = LocalManagementApiAction.CloudflareManagementStatus,
                    config = config,
                    sensitiveConfig = sensitiveConfig,
                )
            }
        cloudflareManagementApiCheckState = result.toDashboardCloudflareManagementApiCheck()
        result
    }

    fun dispatchRootAvailabilityCheck() {
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    rootAvailabilityChecker.check(ROOT_AVAILABILITY_ACTION_TIMEOUT_MILLIS).status
                }
            }.onSuccess { status ->
                explicitRootAvailabilityState = status
                proxyStatusState = proxyStatusState.copy(rootAvailability = status)
            }.onFailure { throwable ->
                Log.w(CELLULAR_PROXY_APP_TAG, "Root availability check failed", throwable)
            }.also {
                rotationActionCompletionVersion += 1L
            }
        }
    }

    fun dispatchPublicIpProbe() {
        coroutineScope.launch {
            currentPublicIpState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        publicIpProbe.probe(
                            route = publicIpProbeRouteForRotation(),
                            endpoint = defaultPublicIpProbeEndpoint(),
                        )
                    }
                }.fold(
                    onSuccess = ::publicIpProbeActionResultFrom,
                    onFailure = { PublicIpProbeActionResult.Unavailable },
                )
            rotationActionCompletionVersion += 1L
            refreshProxyStatus()
        }
    }

    LaunchedEffect(publicIpProbe) {
        dispatchPublicIpProbe()
    }

    fun dispatchLocalManagementApiAction(
        action: LocalManagementApiAction,
        afterResponse: (() -> Unit)? = null,
    ) {
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
                val publicIpProbeResult =
                    publicIpFromManagementApiActionResponse(
                        action = action,
                        response = response,
                    )
                if (publicIpProbeResult != PublicIpProbeActionResult.NotPublicIpAction) {
                    currentPublicIpState = publicIpProbeResult
                }
                cloudflareManagementRoundTripSummary(
                    action = action,
                    response = response,
                )?.let { summary ->
                    cloudflareManagementRoundTripState = summary
                    cloudflareManagementRoundTripVersion += 1L
                }
                if (action == LocalManagementApiAction.CloudflareManagementStatus) {
                    val config = loadSettingsConfig()
                    cloudflareManagementApiCheckState =
                        dashboardCloudflareManagementApiCheckFromSensitiveConfigLoadResult(
                            config = config,
                            result = loadSensitiveConfigResult(),
                        ) { _: SensitiveConfig ->
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
                if (action == LocalManagementApiAction.RootStatus) {
                    dispatchRootAvailabilityCheck()
                }
                cloudflareManagementRoundTripFailureSummary(action)?.let { summary ->
                    cloudflareManagementRoundTripState = summary
                    cloudflareManagementRoundTripVersion += 1L
                }
                if (action == LocalManagementApiAction.CloudflareManagementStatus) {
                    val config = loadSettingsConfig()
                    cloudflareManagementApiCheckState =
                        dashboardCloudflareManagementApiCheckFromSensitiveConfigLoadResult(
                            config = config,
                            result = loadSensitiveConfigResult(),
                        ) { _: SensitiveConfig ->
                            throw throwable
                        }
                }
                Log.w(CELLULAR_PROXY_APP_TAG, "Local management action $action failed", throwable)
            }
            if (action.notifiesCloudflareLifecycleActionCompletion) {
                cloudflareActionCompletionVersion += 1L
            }
            if (action.notifiesRotationActionCompletion) {
                rotationActionCompletionVersion += 1L
            }
            if (afterResponse != null) {
                afterResponse.invoke()
            } else {
                refreshProxyStatus()
            }
        }
    }
    val dispatchLocalManagementApiActionHandler: (LocalManagementApiAction) -> Unit = { action ->
        dispatchLocalManagementApiAction(action)
    }

    val localizedContext =
        remember(context, selectedLanguageState) {
            context.localizedContextFor(selectedLanguageState)
        }
    val localizedConfiguration =
        remember(localizedContext) {
            Configuration(localizedContext.resources.configuration)
        }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
    ) {
        MaterialTheme {
            BoxWithConstraints {
                val navigationChrome = cellularProxyNavigationChromeFor(maxWidth.value.toInt())
                val useNavigationRail = navigationChrome == CellularProxyNavigationChrome.NavigationRail

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
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
                                    .padding(contentPadding)
                                    .statusBarsPadding()
                                    .padding(top = 16.dp)
                                    .consumeWindowInsets(contentPadding),
                        ) {
                            CellularProxyNavigationRail(navController)
                            CellularProxyNavigationHost(
                                navController = navController,
                                onStartProxyService = {
                                    proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Starting)
                                    dispatchForegroundServiceCommandThenRefresh(
                                        action = ForegroundServiceActions.START_PROXY,
                                        dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                        refreshProxyStatus = {
                                            coroutineScope.launch {
                                                delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                                refreshProxyStatus()
                                                dashboardLifecycleActionCompletionVersion += 1L
                                            }
                                        },
                                    )
                                },
                                onStopProxyService = {
                                    proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Stopping)
                                    dispatchForegroundServiceCommandThenRefresh(
                                        action = ForegroundServiceActions.STOP_PROXY,
                                        dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                        refreshProxyStatus = {
                                            coroutineScope.launch {
                                                delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                                refreshProxyStatus()
                                                dashboardLifecycleActionCompletionVersion += 1L
                                            }
                                        },
                                    )
                                },
                                onRestartProxyService = {
                                    proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Stopping)
                                    dispatchForegroundServiceCommandThenRefresh(
                                        action = ForegroundServiceActions.STOP_PROXY,
                                        dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                        refreshProxyStatus = {
                                            coroutineScope.launch {
                                                delay(DASHBOARD_SERVICE_COMMAND_RESTART_DELAY_MILLIS)
                                                proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Starting)
                                                dispatchForegroundServiceCommand(ForegroundServiceActions.START_PROXY)
                                                delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                                refreshProxyStatus()
                                                dashboardLifecycleActionCompletionVersion += 1L
                                            }
                                        },
                                    )
                                },
                                settingsInitialConfigProvider = loadSettingsConfig,
                                settingsSaveConfig = saveSettingsConfig,
                                settingsLoadSensitiveConfig = loadSensitiveConfig,
                                settingsLoadSensitiveConfigResult = loadSensitiveConfigResult,
                                settingsSaveSensitiveConfig = sensitiveRepository::save,
                                selectedLanguageProvider = { selectedLanguageState },
                                onLanguageChange = { language ->
                                    selectedLanguageState = language
                                    context.saveSettingsLanguageOption(language)
                                },
                                logsAuditRowsProvider = loadLogsAuditRows,
                                logsAuditRedactionSecretsProvider = loadLogsAuditRedactionSecrets,
                                proxyStatusProvider = loadProxyStatus,
                                recentTrafficProvider = { recentTrafficState },
                                observedNetworksProvider = loadObservedNetworks,
                                cloudflareEdgeSessionSummaryProvider = {
                                    cloudflareEdgeSessionSummaryState
                                },
                                cloudflareManagementRoundTripProvider = {
                                    cloudflareManagementRoundTripState
                                },
                                cloudflareManagementRoundTripVersionProvider = {
                                    cloudflareManagementRoundTripVersion
                                },
                                cloudflareActionCompletionVersionProvider = {
                                    cloudflareActionCompletionVersion
                                },
                                latestCloudflareManagementApiCheck = cloudflareManagementApiCheckState,
                                publicIpProbeResultProvider = publicIpDiagnosticsProbeResultProvider,
                                localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                                cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                                onRefreshProxyStatus = {
                                    coroutineScope.launch { refreshProxyStatus() }
                                },
                                dashboardActionCompletionVersionProvider = { dashboardLifecycleActionCompletionVersion },
                                dispatchLocalManagementApiAction = dispatchLocalManagementApiActionHandler,
                                onCheckRoot = ::dispatchRootAvailabilityCheck,
                                onProbeCurrentPublicIp = ::dispatchPublicIpProbe,
                                rotationStatusProvider = { rotationStatusState },
                                rotationCooldownRemainingSecondsProvider = { rotationCooldownRemainingSecondsState },
                                actionCompletionVersionProvider = { rotationActionCompletionVersion },
                                publicIpProbeActionResultProvider = { currentPublicIpState },
                                currentPublicIpProvider = {
                                    currentPublicIpForRotationScreen(
                                        probedPublicIp = currentPublicIpState,
                                        statusPublicIp = proxyStatusState.publicIp,
                                    )
                                },
                                onCopyText = { endpointText ->
                                    clipboard.setText(AnnotatedString(endpointText))
                                },
                                onExportLogsAuditBundle = { exportBundle ->
                                    shareLogsAuditExportBundle(context, exportBundle)
                                },
                                onRecordLogsAuditAction = { record ->
                                    CellularProxyLogsAuditStore.logsAuditLog(context, loadLogsAuditRedactionSecrets).record(record)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        CellularProxyNavigationHost(
                            navController = navController,
                            onStartProxyService = {
                                proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Starting)
                                dispatchForegroundServiceCommandThenRefresh(
                                    action = ForegroundServiceActions.START_PROXY,
                                    dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                    refreshProxyStatus = {
                                        coroutineScope.launch {
                                            delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                            refreshProxyStatus()
                                            dashboardLifecycleActionCompletionVersion += 1L
                                        }
                                    },
                                )
                            },
                            onStopProxyService = {
                                proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Stopping)
                                dispatchForegroundServiceCommandThenRefresh(
                                    action = ForegroundServiceActions.STOP_PROXY,
                                    dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                    refreshProxyStatus = {
                                        coroutineScope.launch {
                                            delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                            refreshProxyStatus()
                                            dashboardLifecycleActionCompletionVersion += 1L
                                        }
                                    },
                                )
                            },
                            onRestartProxyService = {
                                proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Stopping)
                                dispatchForegroundServiceCommandThenRefresh(
                                    action = ForegroundServiceActions.STOP_PROXY,
                                    dispatchForegroundServiceCommand = dispatchForegroundServiceCommand,
                                    refreshProxyStatus = {
                                        coroutineScope.launch {
                                            delay(DASHBOARD_SERVICE_COMMAND_RESTART_DELAY_MILLIS)
                                            proxyStatusState = proxyStatusState.copy(state = ProxyServiceState.Starting)
                                            dispatchForegroundServiceCommand(ForegroundServiceActions.START_PROXY)
                                            delay(DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS)
                                            refreshProxyStatus()
                                            dashboardLifecycleActionCompletionVersion += 1L
                                        }
                                    },
                                )
                            },
                            settingsInitialConfigProvider = loadSettingsConfig,
                            settingsSaveConfig = saveSettingsConfig,
                            settingsLoadSensitiveConfig = loadSensitiveConfig,
                            settingsLoadSensitiveConfigResult = loadSensitiveConfigResult,
                            settingsSaveSensitiveConfig = sensitiveRepository::save,
                            selectedLanguageProvider = { selectedLanguageState },
                            onLanguageChange = { language ->
                                selectedLanguageState = language
                                context.saveSettingsLanguageOption(language)
                            },
                            logsAuditRowsProvider = loadLogsAuditRows,
                            logsAuditRedactionSecretsProvider = loadLogsAuditRedactionSecrets,
                            proxyStatusProvider = loadProxyStatus,
                            recentTrafficProvider = { recentTrafficState },
                            observedNetworksProvider = loadObservedNetworks,
                            cloudflareEdgeSessionSummaryProvider = {
                                cloudflareEdgeSessionSummaryState
                            },
                            cloudflareManagementRoundTripProvider = {
                                cloudflareManagementRoundTripState
                            },
                            cloudflareManagementRoundTripVersionProvider = {
                                cloudflareManagementRoundTripVersion
                            },
                            cloudflareActionCompletionVersionProvider = {
                                cloudflareActionCompletionVersion
                            },
                            latestCloudflareManagementApiCheck = cloudflareManagementApiCheckState,
                            publicIpProbeResultProvider = publicIpDiagnosticsProbeResultProvider,
                            localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                            cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                            onRefreshProxyStatus = {
                                coroutineScope.launch { refreshProxyStatus() }
                            },
                            dashboardActionCompletionVersionProvider = { dashboardLifecycleActionCompletionVersion },
                            dispatchLocalManagementApiAction = dispatchLocalManagementApiActionHandler,
                            onCheckRoot = ::dispatchRootAvailabilityCheck,
                            onProbeCurrentPublicIp = ::dispatchPublicIpProbe,
                            rotationStatusProvider = { rotationStatusState },
                            rotationCooldownRemainingSecondsProvider = { rotationCooldownRemainingSecondsState },
                            actionCompletionVersionProvider = { rotationActionCompletionVersion },
                            publicIpProbeActionResultProvider = { currentPublicIpState },
                            currentPublicIpProvider = {
                                currentPublicIpForRotationScreen(
                                    probedPublicIp = currentPublicIpState,
                                    statusPublicIp = proxyStatusState.publicIp,
                                )
                            },
                            onCopyText = { endpointText ->
                                clipboard.setText(AnnotatedString(endpointText))
                            },
                            onExportLogsAuditBundle = { exportBundle ->
                                shareLogsAuditExportBundle(context, exportBundle)
                            },
                            onRecordLogsAuditAction = { record ->
                                CellularProxyLogsAuditStore.logsAuditLog(context, loadLogsAuditRedactionSecrets).record(record)
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding)
                                    .statusBarsPadding()
                                    .padding(top = 16.dp)
                                    .consumeWindowInsets(contentPadding),
                        )
                    }
                }
            }
        }
    }
}

private const val CELLULAR_PROXY_APP_TAG = "CellularProxyApp"
private const val DASHBOARD_SERVICE_COMMAND_RESTART_DELAY_MILLIS = 750L
private const val DASHBOARD_SERVICE_COMMAND_STATUS_REFRESH_DELAY_MILLIS = 500L
private const val OBSERVED_NETWORKS_REFRESH_INTERVAL_MILLIS = 1_000L
private const val ROOT_AVAILABILITY_ACTION_TIMEOUT_MILLIS = 5_000L
private const val UI_PREFERENCES_NAME = "cellularproxy_ui_preferences"
private const val UI_LANGUAGE_TAG_KEY = "languageTag"

private fun Context.loadSettingsLanguageOption(): SettingsLanguageOption = SettingsLanguageOption.fromTag(
    getSharedPreferences(UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(UI_LANGUAGE_TAG_KEY, null),
)

private fun Context.saveSettingsLanguageOption(language: SettingsLanguageOption) {
    getSharedPreferences(UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(UI_LANGUAGE_TAG_KEY, language.localeTag.orEmpty())
        .apply()
}

private fun Context.localizedContextFor(language: SettingsLanguageOption): Context {
    val localeTag = language.localeTag ?: return this
    val configuration = Configuration(resources.configuration)
    val locale = Locale.forLanguageTag(localeTag)
    configuration.setLocales(LocaleList(locale))
    return createConfigurationContext(configuration)
}

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

internal fun dispatchForegroundServiceCommandThenRefresh(
    action: String,
    dispatchForegroundServiceCommand: (String) -> Unit,
    refreshProxyStatus: () -> Unit,
) {
    dispatchForegroundServiceCommand(action)
    refreshProxyStatus()
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

internal val cellularProxyPrimaryNavigationDestinations =
    listOf(
        Dashboard,
        Settings,
        LogsAudit,
    )

internal fun proxyStatusFromLiveStatusOrConfigFallback(
    config: AppConfig,
    liveStatus: () -> ProxyServiceStatus?,
): ProxyServiceStatus = runCatching(liveStatus).getOrNull()
    ?: ProxyServiceStatus.stopped(configuredRoute = config.network.defaultRoutePolicy)

internal fun shouldRestartProxyAfterSettingsSave(status: ProxyServiceStatus): Boolean = status.state == ProxyServiceState.Running

internal fun proxyStatusWithExplicitRootAvailability(
    refreshedStatus: ProxyServiceStatus,
    explicitRootAvailability: RootAvailabilityStatus?,
): ProxyServiceStatus {
    if (
        explicitRootAvailability == null ||
        refreshedStatus.rootAvailability != RootAvailabilityStatus.Unknown
    ) {
        return refreshedStatus
    }
    return refreshedStatus.copy(rootAvailability = explicitRootAvailability)
}

private val LocalManagementApiAction.notifiesRotationActionCompletion: Boolean
    get() =
        this == LocalManagementApiAction.PublicIp ||
            this == LocalManagementApiAction.RotateMobileData ||
            this == LocalManagementApiAction.RotateAirplaneMode

private val LocalManagementApiAction.notifiesCloudflareLifecycleActionCompletion: Boolean
    get() =
        this == LocalManagementApiAction.CloudflareStart ||
            this == LocalManagementApiAction.CloudflareStop ||
            this == LocalManagementApiAction.CloudflareReconnect

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

internal fun dashboardCloudflareManagementApiCheckFromSensitiveConfigLoadResult(
    config: AppConfig,
    result: SensitiveConfigLoadResult,
    request: (SensitiveConfig) -> LocalManagementApiActionResponse,
): DashboardCloudflareManagementApiCheck = cloudflareManagementApiProbeResultFromSensitiveConfigLoadResult(
    config = config,
    result = result,
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

internal fun publicIpFromManagementApiActionResponse(
    action: LocalManagementApiAction,
    response: LocalManagementApiActionResponse,
): PublicIpProbeActionResult {
    if (action != LocalManagementApiAction.PublicIp || !response.isSuccessful) {
        return PublicIpProbeActionResult.NotPublicIpAction
    }
    return runCatching {
        val publicIp =
            Json
                .parseToJsonElement(response.body)
                .jsonObject
                .nullableStringValue("publicIp")
        publicIp?.let(PublicIpProbeActionResult::Observed) ?: PublicIpProbeActionResult.Unavailable
    }.getOrDefault(PublicIpProbeActionResult.Unavailable)
}

internal fun publicIpProbeActionResultFrom(result: PublicIpProbeResult): PublicIpProbeActionResult = when (result) {
    is PublicIpProbeResult.Success -> PublicIpProbeActionResult.Observed(result.publicIp)
    is PublicIpProbeResult.Failed -> PublicIpProbeActionResult.Unavailable
}

internal fun currentPublicIpForRotationScreen(
    probedPublicIp: PublicIpProbeActionResult,
    statusPublicIp: String?,
): String? = when (probedPublicIp) {
    PublicIpProbeActionResult.NotPublicIpAction -> statusPublicIp
    is PublicIpProbeActionResult.Observed -> probedPublicIp.value
    PublicIpProbeActionResult.Unavailable -> null
}

internal fun currentPublicIpForDashboard(
    probedPublicIp: PublicIpProbeActionResult,
    statusPublicIp: String?,
): String? = when (probedPublicIp) {
    PublicIpProbeActionResult.NotPublicIpAction -> statusPublicIp
    is PublicIpProbeActionResult.Observed -> probedPublicIp.value
    PublicIpProbeActionResult.Unavailable -> statusPublicIp
}

internal fun publicIpProbeRouteForRotation(): RouteTarget = RouteTarget.Cellular

internal sealed interface PublicIpProbeActionResult {
    data object NotPublicIpAction : PublicIpProbeActionResult

    data object Unavailable : PublicIpProbeActionResult

    data class Observed(
        val value: String,
    ) : PublicIpProbeActionResult
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
internal fun CellularProxyNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        cellularProxyPrimaryNavigationDestinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigateToCellularProxyTopLevelDestination(destination)
                },
                modifier = Modifier.testTag("navigation-item-${destination.route}"),
                icon = {
                    Icon(destination.icon, contentDescription = destination.label)
                },
                label = {
                    Text(destination.shortLabel)
                },
            )
        }
    }
}

@Composable
internal fun CellularProxyNavigationRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationRail {
        cellularProxyPrimaryNavigationDestinations.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigateToCellularProxyTopLevelDestination(destination)
                },
                modifier = Modifier.testTag("navigation-item-${destination.route}"),
                icon = {
                    Icon(destination.icon, contentDescription = destination.label)
                },
            )
        }
    }
}

internal fun NavHostController.navigateToCellularProxyTopLevelDestination(
    destination: CellularProxyNavigationDestination,
) {
    if (destination == Dashboard) {
        navigate(destination.route) {
            launchSingleTop = true
            restoreState = false
            popUpTo(graph.findStartDestination().id) {
                saveState = false
            }
        }
        return
    }

    navigate(destination.route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

internal fun NavHostController.navigateToCellularProxyDestination(
    destination: CellularProxyNavigationDestination,
) {
    if (destination in cellularProxyPrimaryNavigationDestinations) {
        navigateToCellularProxyTopLevelDestination(destination)
    } else {
        navigate(destination.route) {
            launchSingleTop = true
        }
    }
}

@Composable
internal fun CellularProxyNavigationHost(
    navController: NavHostController,
    onStartProxyService: () -> Unit,
    onStopProxyService: () -> Unit,
    onRestartProxyService: () -> Unit,
    settingsInitialConfigProvider: () -> AppConfig,
    settingsSaveConfig: (AppConfig) -> Unit,
    settingsLoadSensitiveConfig: () -> SensitiveConfig,
    settingsLoadSensitiveConfigResult: () -> SensitiveConfigLoadResult = {
        SensitiveConfigLoadResult.Loaded(settingsLoadSensitiveConfig())
    },
    settingsSaveSensitiveConfig: (SensitiveConfig) -> Unit,
    selectedLanguageProvider: () -> SettingsLanguageOption = { SettingsLanguageOption.System },
    onLanguageChange: (SettingsLanguageOption) -> Unit = {},
    logsAuditRowsProvider: () -> List<LogsAuditScreenInputRow>,
    logsAuditRedactionSecretsProvider: () -> LogRedactionSecrets,
    proxyStatusProvider: () -> ProxyServiceStatus,
    recentTrafficProvider: () -> DashboardTrafficSummary?,
    observedNetworksProvider: () -> List<NetworkDescriptor>,
    cloudflareEdgeSessionSummaryProvider: () -> String? = { null },
    cloudflareManagementRoundTripProvider: () -> String?,
    cloudflareManagementRoundTripVersionProvider: () -> Long = { 0L },
    cloudflareActionCompletionVersionProvider: () -> Long = { 0L },
    latestCloudflareManagementApiCheck: DashboardCloudflareManagementApiCheck,
    publicIpProbeResultProvider: () -> PublicIpDiagnosticsProbeResult = { PublicIpDiagnosticsProbeResult.Unavailable },
    localManagementApiProbeResultProvider: () -> LocalManagementApiProbeResult,
    cloudflareManagementApiProbeResultProvider: () -> CloudflareManagementApiProbeResult,
    onRefreshProxyStatus: () -> Unit,
    dashboardActionCompletionVersionProvider: () -> Long = { 0L },
    dispatchLocalManagementApiAction: (LocalManagementApiAction) -> Unit,
    onCheckRoot: () -> Unit = { dispatchLocalManagementApiAction(LocalManagementApiAction.RootStatus) },
    onProbeCurrentPublicIp: () -> Unit = { dispatchLocalManagementApiAction(LocalManagementApiAction.PublicIp) },
    rotationStatusProvider: () -> RotationStatus,
    rotationCooldownRemainingSecondsProvider: () -> Long?,
    actionCompletionVersionProvider: () -> Long = { 0L },
    publicIpProbeActionResultProvider: () -> PublicIpProbeActionResult = {
        PublicIpProbeActionResult.NotPublicIpAction
    },
    currentPublicIpProvider: () -> String?,
    onCopyText: (String) -> Unit,
    onExportLogsAuditBundle: (LogsAuditScreenExportBundle) -> Unit,
    onRecordLogsAuditAction: (PersistedLogsAuditRecord) -> Unit,
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
                    val observedStatus = proxyStatusProvider()
                    val status =
                        observedStatus.copy(
                            publicIp =
                                currentPublicIpForDashboard(
                                    probedPublicIp = publicIpProbeActionResultProvider(),
                                    statusPublicIp = observedStatus.publicIp,
                                ),
                        )
                    val sensitiveConfigResult = settingsLoadSensitiveConfigResult()
                    val sensitiveConfigDashboardInputs = sensitiveConfigDashboardInputs(sensitiveConfigResult)
                    DashboardStatusModel.from(
                        config = config,
                        status = status,
                        recentLogs = dashboardLogSummariesFromLogsAuditRows(logsAuditRowsProvider()),
                        redactionSecrets = logRedactionSecretsFromSensitiveConfigLoadResult(sensitiveConfigResult),
                        latestCloudflareManagementApiCheck = latestCloudflareManagementApiCheck,
                        managementApiTokenPresent = sensitiveConfigDashboardInputs.managementApiTokenPresent,
                        invalidSensitiveConfigReason = sensitiveConfigDashboardInputs.invalidSensitiveConfigReason,
                        recentTraffic = recentTrafficProvider(),
                        rotationStatus = rotationStatusProvider(),
                        rotationCooldownRemainingSeconds = rotationCooldownRemainingSecondsProvider(),
                    )
                },
                onStartProxyService = onStartProxyService,
                onStopProxyService = onStopProxyService,
                onRestartProxyService = onRestartProxyService,
                onRefreshStatus = onRefreshProxyStatus,
                onOpenRiskDetails = { navController.navigateToCellularProxyDestination(LogsAudit) },
                onOpenSettings = { navController.navigateToCellularProxyDestination(Settings) },
                onOpenCloudflare = { navController.navigateToCellularProxyDestination(Cloudflare) },
                onOpenRotation = { navController.navigateToCellularProxyDestination(Rotation) },
                onOpenLogs = { navController.navigateToCellularProxyDestination(LogsAudit) },
                onOpenDiagnostics = { navController.navigateToCellularProxyDestination(Diagnostics) },
                actionCompletionVersionProvider = dashboardActionCompletionVersionProvider,
                onCopyProxyEndpointText = onCopyText,
                onRecordDashboardAuditAction = onRecordLogsAuditAction,
            )
        }
        composable(Settings.route) {
            CellularProxySettingsRoute(
                initialConfigProvider = settingsInitialConfigProvider,
                saveConfig = settingsSaveConfig,
                observedNetworksProvider = observedNetworksProvider,
                selectedLanguageProvider = selectedLanguageProvider,
                onLanguageChange = onLanguageChange,
                loadSensitiveConfig = settingsLoadSensitiveConfig,
                loadSensitiveConfigResult = settingsLoadSensitiveConfigResult,
                saveSensitiveConfig = settingsSaveSensitiveConfig,
                onRecordSettingsAuditAction = onRecordLogsAuditAction,
            )
        }
        composable(Cloudflare.route) {
            CellularProxyCloudflareRoute(
                configProvider = settingsInitialConfigProvider,
                tokenStatusProvider = {
                    cloudflareTokenStatusFrom(settingsLoadSensitiveConfigResult())
                },
                tunnelStatusProvider = { proxyStatusProvider().cloudflare },
                edgeSessionSummaryProvider = cloudflareEdgeSessionSummaryProvider,
                managementApiRoundTripProvider = cloudflareManagementRoundTripProvider,
                managementApiRoundTripVersionProvider = cloudflareManagementRoundTripVersionProvider,
                actionCompletionVersionProvider = cloudflareActionCompletionVersionProvider,
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
                onRecordCloudflareAuditAction = onRecordLogsAuditAction,
            )
        }
        composable(Rotation.route) {
            CellularProxyRotationRoute(
                configProvider = settingsInitialConfigProvider,
                rotationStatusProvider = { rotationStatusProvider() },
                currentPublicIpProvider = { currentPublicIpProvider() },
                rootAvailabilityProvider = { proxyStatusProvider().rootAvailability },
                cooldownRemainingSecondsProvider = { rotationCooldownRemainingSecondsProvider() },
                activeConnectionsProvider = { proxyStatusProvider().metrics.activeConnections },
                actionCompletionVersionProvider = actionCompletionVersionProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                onCheckRoot = onCheckRoot,
                onProbeCurrentPublicIp = onProbeCurrentPublicIp,
                onRotateMobileData = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.RotateMobileData)
                },
                onRotateAirplaneMode = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.RotateAirplaneMode)
                },
                onCopyRotationDiagnosticsText = onCopyText,
                onRecordRotationAuditAction = onRecordLogsAuditAction,
            )
        }
        composable(Diagnostics.route) {
            CellularProxyDiagnosticsRoute(
                configProvider = settingsInitialConfigProvider,
                proxyStatusProvider = proxyStatusProvider,
                observedNetworksProvider = observedNetworksProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                publicIpProbeResultProvider = publicIpProbeResultProvider,
                localManagementApiProbeResultProvider = localManagementApiProbeResultProvider,
                cloudflareManagementApiProbeResultProvider = cloudflareManagementApiProbeResultProvider,
                onCopyDiagnosticsSummaryText = onCopyText,
                onRecordDiagnosticsAuditAction = onRecordLogsAuditAction,
            )
        }
        composable(LogsAudit.route) {
            CellularProxyLogsAuditRoute(
                logsAuditRowsProvider = logsAuditRowsProvider,
                redactionSecretsProvider = logsAuditRedactionSecretsProvider,
                onCopyLogsAuditText = onCopyText,
                onExportLogsAuditBundle = onExportLogsAuditBundle,
                onRecordLogsAuditAction = onRecordLogsAuditAction,
            )
        }
    }
}

private fun loadOrCreateSensitiveConfig(
    repository: SensitiveConfigRepository,
    generator: SensitiveConfigGenerator,
): SensitiveConfig {
    val result = repository.load()
    return sensitiveConfigFromLoadResultOrCreateDefault(result) {
        generator
            .generateDefaultSensitiveConfig()
            .also(repository::save)
    } ?: when (result) {
        is SensitiveConfigLoadResult.Invalid -> error("Sensitive config is invalid: ${result.reason}")
        SensitiveConfigLoadResult.MissingRequiredSecrets,
        is SensitiveConfigLoadResult.Loaded,
        -> error("Sensitive config could not be loaded after default creation")
    }
}

internal fun sensitiveConfigFromLoadResultOrCreateDefault(
    result: SensitiveConfigLoadResult,
    createDefaultSensitiveConfig: () -> SensitiveConfig,
): SensitiveConfig? = when (result) {
    is SensitiveConfigLoadResult.Loaded -> result.config
    SensitiveConfigLoadResult.MissingRequiredSecrets -> createDefaultSensitiveConfig()
    is SensitiveConfigLoadResult.Invalid -> null
}

internal data class SensitiveConfigDashboardInputs(
    val managementApiTokenPresent: Boolean,
    val invalidSensitiveConfigReason: SensitiveConfigInvalidReason?,
)

internal fun sensitiveConfigDashboardInputs(
    result: SensitiveConfigLoadResult,
): SensitiveConfigDashboardInputs = when (result) {
    is SensitiveConfigLoadResult.Loaded ->
        SensitiveConfigDashboardInputs(
            managementApiTokenPresent = result.config.managementApiToken.isNotBlank(),
            invalidSensitiveConfigReason = null,
        )
    SensitiveConfigLoadResult.MissingRequiredSecrets ->
        SensitiveConfigDashboardInputs(
            managementApiTokenPresent = false,
            invalidSensitiveConfigReason = null,
        )
    is SensitiveConfigLoadResult.Invalid ->
        SensitiveConfigDashboardInputs(
            managementApiTokenPresent = result.reason.managementApiTokenPresentForDashboard(),
            invalidSensitiveConfigReason = result.reason,
        )
}

private fun SensitiveConfigInvalidReason.managementApiTokenPresentForDashboard(): Boolean = when (this) {
    SensitiveConfigInvalidReason.InvalidManagementApiToken,
    SensitiveConfigInvalidReason.PartiallyMissingRequiredSecrets,
    SensitiveConfigInvalidReason.UndecryptableSecret,
    SensitiveConfigInvalidReason.InvalidProxyCredential,
    -> false
    SensitiveConfigInvalidReason.InvalidCloudflareTunnelToken,
    -> true
}

internal fun logRedactionSecretsFromSensitiveConfigLoadResult(
    result: SensitiveConfigLoadResult,
): LogRedactionSecrets = when (result) {
    is SensitiveConfigLoadResult.Loaded ->
        LogRedactionSecrets(
            managementApiToken = result.config.managementApiToken,
            proxyCredential = result.config.proxyCredential.canonicalBasicPayload(),
            cloudflareTunnelToken = result.config.cloudflareTunnelToken,
        )
    SensitiveConfigLoadResult.MissingRequiredSecrets,
    is SensitiveConfigLoadResult.Invalid,
    -> LogRedactionSecrets()
}
