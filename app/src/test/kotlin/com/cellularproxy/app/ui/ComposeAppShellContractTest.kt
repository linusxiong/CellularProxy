package com.cellularproxy.app.ui

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
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

    private fun repoRoot() = Path(requireNotNull(System.getProperty("user.dir"))).let { workingDirectory ->
        if (workingDirectory.resolve("settings.gradle.kts").toFile().exists()) {
            workingDirectory
        } else {
            assertNotNull(workingDirectory.parent)
        }
    }
}
