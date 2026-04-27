package com.cellularproxy.app.ui

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            shellSource.contains("CellularProxyDashboardScreen()"),
            "Dashboard route must render the dedicated status screen instead of the generic placeholder.",
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
            "Recent high-severity errors",
            "Start proxy",
            "Stop proxy",
            "Refresh status",
            "Copy proxy endpoint",
        ).forEach { label ->
            assertTrue(
                dashboardSource.contains(label),
                "Dashboard screen must expose `$label`.",
            )
        }
    }

    private fun repoRoot() = Path(requireNotNull(System.getProperty("user.dir"))).let { workingDirectory ->
        if (workingDirectory.resolve("settings.gradle.kts").toFile().exists()) {
            workingDirectory
        } else {
            assertNotNull(workingDirectory.parent)
        }
    }
}
