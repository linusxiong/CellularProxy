package com.cellularproxy.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class CellularProxyNavigationDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard(
        route = "dashboard",
        label = "Dashboard",
        icon = Icons.Filled.Dashboard,
    ),
    Settings(
        route = "settings",
        label = "Settings",
        icon = Icons.Filled.Settings,
    ),
    Cloudflare(
        route = "cloudflare",
        label = "Cloudflare",
        icon = Icons.Filled.Cloud,
    ),
    Rotation(
        route = "rotation",
        label = "Rotation",
        icon = Icons.AutoMirrored.Filled.RotateRight,
    ),
    Diagnostics(
        route = "diagnostics",
        label = "Diagnostics",
        icon = Icons.Filled.BugReport,
    ),
    LogsAudit(
        route = "logs-audit",
        label = "Logs/Audit",
        icon = Icons.AutoMirrored.Filled.Article,
    ),
}
