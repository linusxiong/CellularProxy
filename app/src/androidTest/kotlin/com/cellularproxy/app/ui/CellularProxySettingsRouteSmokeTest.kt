package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigLoadResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CellularProxySettingsRouteSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CellularProxyComposeTestActivity>()

    @Test
    fun routeSelectorShowsOnlyCurrentlyAvailableConcreteExits() {
        composeRule.setContent {
            MaterialTheme {
                CellularProxySettingsRoute(
                    observedNetworksProvider = {
                        listOf(
                            network("wifi", NetworkCategory.WiFi, isAvailable = false),
                            network("cell", NetworkCategory.Cellular, isAvailable = true),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("Default route").assertIsDisplayed()
        composeRule.onNodeWithText("Automatic").assertIsDisplayed()
        composeRule.onNodeWithText("Cellular").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Wi-Fi").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("VPN").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun routeHidesOptionalSecretsUntilTheirFeatureIsEnabledAndExposesLanguageChoice() {
        val selectedLanguageState = mutableStateOf(SettingsLanguageOption.System)
        val configState =
            mutableStateOf(
                AppConfig.default().copy(
                    proxy = AppConfig.default().proxy.copy(authEnabled = false),
                    cloudflare = AppConfig.default().cloudflare.copy(enabled = false),
                ),
            )

        composeRule.setContent {
            MaterialTheme {
                CellularProxySettingsRoute(
                    initialConfigProvider = { configState.value },
                    saveConfig = { config -> configState.value = config },
                    selectedLanguageProvider = { selectedLanguageState.value },
                    onLanguageChange = { selectedLanguageState.value = it },
                )
            }
        }

        composeRule.onNodeWithText("Language").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("System").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("English").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("中文").performScrollTo().assertIsDisplayed()

        assertTrue(composeRule.onAllNodesWithText("Proxy username").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Proxy password").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Cloudflare tunnel token").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Cloudflare hostname").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("Proxy authentication").performScrollTo().performClick()
        composeRule.onNodeWithText("Proxy username").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Proxy password").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Cloudflare enabled").performScrollTo().performClick()
        composeRule.onNodeWithText("Cloudflare tunnel token").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cloudflare hostname").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("中文").performScrollTo().performClick()
        assertEquals(SettingsLanguageOption.ChineseSimplified, selectedLanguageState.value)
    }

    @Test
    fun routeSavesEditedProxySettingsAndReloadsProviderState() {
        val configState = mutableStateOf(AppConfig.default())

        composeRule.setContent {
            MaterialTheme {
                CellularProxySettingsRoute(
                    initialConfigProvider = { configState.value },
                    saveConfig = { config -> configState.value = config },
                )
            }
        }

        composeRule.onNode(hasText("8080") and hasSetTextAction()).assertIsDisplayed()

        composeRule
            .onNode(hasText("8080") and hasSetTextAction())
            .performTextReplacement("8181")
        composeRule
            .onNodeWithText("Save settings")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            configState.value.proxy.listenPort == 8181
        }
        assertEquals(8181, configState.value.proxy.listenPort)
        composeRule
            .onNode(hasText("8181") and hasSetTextAction())
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Save settings")
            .performScrollTo()
            .assertIsNotEnabled()

        configState.value =
            configState.value.copy(
                proxy =
                    ProxyConfig(
                        listenHost = "127.0.0.1",
                        listenPort = 8282,
                        authEnabled = true,
                    ),
            )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("8282") and hasSetTextAction())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNode(hasText("127.0.0.1") and hasSetTextAction())
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun routeRendersValidationErrorsAndBlocksInvalidSaves() {
        val configState = mutableStateOf(AppConfig.default())

        composeRule.setContent {
            MaterialTheme {
                CellularProxySettingsRoute(
                    initialConfigProvider = { configState.value },
                    saveConfig = { config -> configState.value = config },
                )
            }
        }

        composeRule
            .onNode(hasText("8080") and hasSetTextAction())
            .performTextReplacement("70000")

        composeRule
            .onNodeWithText("Listen port must be between 1 and 65535.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Save settings")
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(8080, configState.value.proxy.listenPort)
    }

    @Test
    fun routeSavesSensitiveCloudflareTokenAndReloadsPresenceState() {
        val tunnelToken = validCloudflareTunnelToken()
        val configState = mutableStateOf(AppConfig.default())
        val sensitiveConfigState = mutableStateOf(sensitiveConfig())

        composeRule.setContent {
            MaterialTheme {
                CellularProxySettingsRoute(
                    initialConfigProvider = { configState.value },
                    saveConfig = { config -> configState.value = config },
                    loadSensitiveConfigResult = { SensitiveConfigLoadResult.Loaded(sensitiveConfigState.value) },
                    saveSensitiveConfig = { config -> sensitiveConfigState.value = config },
                )
            }
        }

        composeRule
            .onNodeWithText("Tunnel token status: Missing")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Cloudflare enabled")
            .performScrollTo()
            .performClick()

        composeRule
            .onNode(hasText("Cloudflare tunnel token") and hasSetTextAction())
            .performScrollTo()
            .performTextReplacement(tunnelToken)
        composeRule
            .onNodeWithText("Tunnel token status: Edited")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Save settings")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            sensitiveConfigState.value.cloudflareTunnelToken == tunnelToken &&
                configState.value.cloudflare.tunnelTokenPresent
        }

        assertEquals(tunnelToken, sensitiveConfigState.value.cloudflareTunnelToken)
        assertTrue(configState.value.cloudflare.tunnelTokenPresent)
        composeRule
            .onNodeWithText("Tunnel token status: Present")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Save settings")
            .performScrollTo()
            .assertIsNotEnabled()
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
)

private fun validCloudflareTunnelToken(): String = "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="

private fun network(
    id: String,
    category: NetworkCategory,
    isAvailable: Boolean,
): NetworkDescriptor = NetworkDescriptor(
    id = id,
    category = category,
    displayName = id,
    isAvailable = isAvailable,
)
