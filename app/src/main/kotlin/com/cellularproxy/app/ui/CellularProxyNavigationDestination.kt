package com.cellularproxy.app.ui

enum class CellularProxyNavigationDestination(
    val route: String,
    val label: String,
) {
    Dashboard(
        route = "dashboard",
        label = "Dashboard",
    ),
    Settings(
        route = "settings",
        label = "Settings",
    ),
    Cloudflare(
        route = "cloudflare",
        label = "Cloudflare",
    ),
    Rotation(
        route = "rotation",
        label = "Rotation",
    ),
    Diagnostics(
        route = "diagnostics",
        label = "Diagnostics",
    ),
    LogsAudit(
        route = "logs-audit",
        label = "Logs/Audit",
    ),
}
