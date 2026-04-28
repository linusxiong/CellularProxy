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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cellularproxy.app.config.CellularProxyPlainConfigStore
import com.cellularproxy.app.config.SecureRandomSensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigGenerator
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.app.config.SensitiveConfigRepository
import com.cellularproxy.app.config.SensitiveConfigRepositoryFactory
import com.cellularproxy.app.service.CellularProxyForegroundService
import com.cellularproxy.app.service.ForegroundServiceActions
import com.cellularproxy.app.service.LocalManagementApiAction
import com.cellularproxy.app.service.LocalManagementApiActionDispatcher
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Cloudflare
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Dashboard
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Diagnostics
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.LogsAudit
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Rotation
import com.cellularproxy.app.ui.CellularProxyNavigationDestination.Settings
import com.cellularproxy.shared.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    val dispatchForegroundServiceCommand: (String) -> Unit = { action ->
        context.startForegroundService(
            Intent(context, CellularProxyForegroundService::class.java).setAction(action),
        )
    }
    val loadSettingsConfig: () -> AppConfig = {
        runBlocking { plainConfigRepository.load() }
    }
    val saveSettingsConfig: (AppConfig) -> Unit = { config ->
        runBlocking { plainConfigRepository.save(config) }
    }
    val loadSensitiveConfig: () -> SensitiveConfig = {
        loadOrCreateSensitiveConfig(
            repository = sensitiveRepository,
            generator = sensitiveConfigGenerator,
        )
    }
    val dispatchLocalManagementApiAction: (LocalManagementApiAction) -> Unit = { action ->
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                localManagementApiActionDispatcher.dispatch(
                    action = action,
                    config = loadSettingsConfig(),
                    sensitiveConfig = loadSensitiveConfig(),
                )
            }.onSuccess { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        CELLULAR_PROXY_APP_TAG,
                        "Local management action $action returned HTTP ${response.statusCode}",
                    )
                }
            }.onFailure { throwable ->
                Log.w(CELLULAR_PROXY_APP_TAG, "Local management action $action failed", throwable)
            }
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
                            dispatchLocalManagementApiAction = dispatchLocalManagementApiAction,
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
                        dispatchLocalManagementApiAction = dispatchLocalManagementApiAction,
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
    dispatchLocalManagementApiAction: (LocalManagementApiAction) -> Unit,
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
                onStartProxyService = onStartProxyService,
                onStopProxyService = onStopProxyService,
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
                onStartTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareStart)
                },
                onStopTunnel = {
                    dispatchLocalManagementApiAction(LocalManagementApiAction.CloudflareStop)
                },
                onReconnectTunnel = {
                },
                onTestManagementTunnel = {
                },
                onCopyDiagnosticsText = onCopyText,
            )
        }
        composable(Rotation.route) {
            CellularProxyRotationRoute(
                onCheckRoot = {
                },
                onProbeCurrentPublicIp = {
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
                onCopyDiagnosticsSummaryText = onCopyText,
            )
        }
        composable(LogsAudit.route) {
            CellularProxyLogsAuditRoute(
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
