package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cellularproxy.app.diagnostics.CloudflareManagementApiProbeResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CellularProxyDiagnosticsRouteSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitCloudflareManagementCheckRunsOnlyWhenClicked() {
        val cloudflareManagementProbeCalls = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                CellularProxyDiagnosticsRoute(
                    cloudflareManagementApiProbeResultProvider = {
                        cloudflareManagementProbeCalls.incrementAndGet()
                        CloudflareManagementApiProbeResult.Authenticated
                    },
                )
            }
        }

        composeRule
            .onNodeWithText("Run non-Cloudflare checks")
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText("5 of 7 checks complete"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, cloudflareManagementProbeCalls.get())

        composeRule
            .onNodeWithText("Run Cloudflare management API")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            cloudflareManagementProbeCalls.get() == 1
        }
        composeRule
            .onNodeWithText("Cloudflare management API authenticated")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
