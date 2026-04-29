package com.cellularproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CellularProxyRotationRouteSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CellularProxyComposeTestActivity>()

    @Test
    fun routeConfirmsUnsafeRotationAndCopiesRedactedDiagnostics() {
        val rotationStatus = mutableStateOf(RotationStatus.idle())
        val copiedDiagnostics = mutableListOf<String>()
        val auditRecords = mutableListOf<PersistedLogsAuditRecord>()
        var mobileDataRotationRequests = 0
        var rootChecks = 0
        var publicIpProbes = 0

        composeRule.setContent {
            MaterialTheme {
                CellularProxyRotationRoute(
                    configProvider = ::rootEnabledConfig,
                    rotationStatusProvider = { rotationStatus.value },
                    currentPublicIpProvider = { "203.0.113.44" },
                    rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                    redactionSecretsProvider = {
                        LogRedactionSecrets(
                            proxyCredential = "203.0.113.44",
                        )
                    },
                    onCheckRoot = {
                        rootChecks += 1
                    },
                    onProbeCurrentPublicIp = {
                        publicIpProbes += 1
                    },
                    onRotateMobileData = {
                        mobileDataRotationRequests += 1
                        rotationStatus.value =
                            RotationStatus(
                                state = RotationState.CheckingRoot,
                                operation = RotationOperation.MobileData,
                            )
                    },
                    onCopyRotationDiagnosticsText = copiedDiagnostics::add,
                    onRecordRotationAuditAction = auditRecords::add,
                    auditOccurredAtEpochMillisProvider = { 4_242L },
                )
            }
        }

        composeRule
            .onNodeWithText("Available")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("[REDACTED]")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Check root")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rootChecks == 1
        }
        composeRule
            .onNodeWithText("Probe current public IP")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            publicIpProbes == 1
        }

        composeRule
            .onNodeWithText("Rotate mobile data")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithText("Confirm mobile data rotation")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Confirm")
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            mobileDataRotationRequests == 1 &&
                composeRule
                    .onAllNodes(hasText("CheckingRoot"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        composeRule
            .onNodeWithText("Copy diagnostics")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            copiedDiagnostics.isNotEmpty()
        }

        val copiedText = copiedDiagnostics.single()
        assertTrue(copiedText.contains("Current phase: CheckingRoot"))
        assertTrue(copiedText.contains("Current public IP: [REDACTED]"))
        assertFalse(copiedText.contains("203.0.113.44"))
        assertEquals(
            listOf(4_242L, 4_242L, 4_242L, 4_242L),
            auditRecords.map(PersistedLogsAuditRecord::occurredAtEpochMillis),
        )
        assertEquals(
            listOf(
                "Rotation check_root",
                "Rotation probe_current_public_ip",
                "Rotation rotate_mobile_data",
                "Rotation copy_diagnostics",
            ),
            auditRecords.map(PersistedLogsAuditRecord::title),
        )
    }

    @Test
    fun routeConfirmsAirplaneModeRotationBeforeDispatching() {
        val auditRecords = mutableListOf<PersistedLogsAuditRecord>()
        var airplaneModeRotationRequests = 0

        composeRule.setContent {
            MaterialTheme {
                CellularProxyRotationRoute(
                    configProvider = ::rootEnabledConfig,
                    rotationStatusProvider = { RotationStatus.idle() },
                    rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                    onRotateAirplaneMode = {
                        airplaneModeRotationRequests += 1
                    },
                    onRecordRotationAuditAction = auditRecords::add,
                    auditOccurredAtEpochMillisProvider = { 5_151L },
                )
            }
        }

        composeRule
            .onNodeWithText("Rotate airplane mode")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            airplaneModeRotationRequests == 0 &&
                composeRule
                    .onAllNodes(hasText("Confirm airplane mode rotation"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        composeRule
            .onNodeWithText("Confirm")
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            airplaneModeRotationRequests == 1
        }
        assertEquals(
            listOf(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 5_151L,
                    category = LogsAuditRecordCategory.Rotation,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Rotation rotate_airplane_mode",
                    detail = "action=rotate_airplane_mode phase=Idle",
                ),
            ),
            auditRecords,
        )
    }
}

private fun rootEnabledConfig(): AppConfig {
    val defaultConfig = AppConfig.default()
    return defaultConfig.copy(
        root =
            defaultConfig.root.copy(
                operationsEnabled = true,
            ),
    )
}
