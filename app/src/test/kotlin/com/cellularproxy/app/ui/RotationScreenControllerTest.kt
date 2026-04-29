package com.cellularproxy.app.ui

import com.cellularproxy.app.audit.LogsAuditRecordCategory
import com.cellularproxy.app.audit.LogsAuditRecordSeverity
import com.cellularproxy.app.audit.PersistedLogsAuditRecord
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationScreenControllerTest {
    @Test
    fun `controller dispatches available rotation actions and refreshes screen state`() {
        var rotationStatus = RotationStatus.idle()
        val actions = mutableListOf<RotationScreenAction>()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                actionHandler = { action ->
                    actions += action
                    rotationStatus =
                        when (action) {
                            RotationScreenAction.RotateMobileData ->
                                RotationStatus(
                                    state = RotationState.CheckingRoot,
                                    operation = RotationOperation.MobileData,
                                )
                            else -> rotationStatus
                        }
                },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)
        assertEquals("CheckingRoot", controller.state.currentPhase)
        assertFalse(RotationScreenAction.RotateAirplaneMode in controller.state.availableActions)
    }

    @Test
    fun `controller suppresses unavailable rotation actions`() {
        val actions = mutableListOf<RotationScreenAction>()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Unavailable },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)

        assertTrue(actions.isEmpty())
        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CopyDiagnostics,
            ),
            controller.state.availableActions,
        )
    }

    @Test
    fun `controller suppresses duplicate unsafe rotations until operation refresh completes`() {
        val actions = mutableListOf<RotationScreenAction>()
        var rotationStatus = RotationStatus.idle()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals(listOf(RotationScreenAction.RotateMobileData), actions)
        assertFalse(RotationScreenAction.RotateMobileData in controller.state.availableActions)
        assertFalse(RotationScreenAction.RotateAirplaneMode in controller.state.availableActions)

        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals(
            listOf(
                RotationScreenAction.RotateMobileData,
                RotationScreenAction.RotateMobileData,
            ),
            actions,
        )

        rotationStatus =
            RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.MobileData,
            )
        controller.handle(RotationScreenEvent.Refresh)
        rotationStatus =
            RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.MobileData,
                failureReason = RotationFailureReason.RootUnavailable,
            )
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.RotateMobileData)

        assertEquals(
            listOf(
                RotationScreenAction.RotateMobileData,
                RotationScreenAction.RotateMobileData,
                RotationScreenAction.RotateMobileData,
            ),
            actions,
        )
    }

    @Test
    fun `controller suppresses duplicate check root and public ip probe actions until provider state changes`() {
        val actions = mutableListOf<RotationScreenAction>()
        var rootAvailability = RootAvailabilityStatus.Unknown
        var currentPublicIp: String? = null
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { rootAvailability },
                currentPublicIpProvider = { currentPublicIp },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.CheckRoot)
        controller.handle(RotationScreenEvent.CheckRoot)
        controller.handle(RotationScreenEvent.ProbeCurrentPublicIp)
        controller.handle(RotationScreenEvent.ProbeCurrentPublicIp)

        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
            ),
            actions,
        )
        assertFalse(RotationScreenAction.CheckRoot in controller.state.availableActions)
        assertFalse(RotationScreenAction.ProbeCurrentPublicIp in controller.state.availableActions)
        assertEquals("In progress: Check root", controller.state.pendingOperation)
        assertEquals(setOf(RotationScreenWarning.OperationInProgress), controller.state.warnings)

        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CheckRoot)
        controller.handle(RotationScreenEvent.ProbeCurrentPublicIp)

        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
            ),
            actions,
        )

        rootAvailability = RootAvailabilityStatus.Available
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CheckRoot)
        currentPublicIp = "203.0.113.44"
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.ProbeCurrentPublicIp)

        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
            ),
            actions,
        )
    }

    @Test
    fun `controller emits audit records for dispatched rotation actions`() {
        val actions = mutableListOf<RotationScreenAction>()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                currentPublicIpProvider = { "203.0.113.44" },
                auditActionsEnabled = true,
                auditOccurredAtEpochMillisProvider = { 12_345 },
                actionHandler = { action -> actions += action },
            )

        controller.handle(RotationScreenEvent.CheckRoot)
        controller.handle(RotationScreenEvent.ProbeCurrentPublicIp)
        controller.handle(RotationScreenEvent.RotateMobileData)
        controller.handle(RotationScreenEvent.CopyDiagnostics)

        assertEquals(
            listOf(
                RotationScreenAction.CheckRoot,
                RotationScreenAction.ProbeCurrentPublicIp,
                RotationScreenAction.RotateMobileData,
            ),
            actions,
        )
        assertEquals(
            listOf(
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 12_345,
                    category = LogsAuditRecordCategory.Rotation,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Rotation check_root",
                    detail = "action=check_root phase=Idle",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 12_345,
                    category = LogsAuditRecordCategory.Rotation,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Rotation probe_current_public_ip",
                    detail = "action=probe_current_public_ip phase=Idle",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 12_345,
                    category = LogsAuditRecordCategory.Rotation,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Rotation rotate_mobile_data",
                    detail = "action=rotate_mobile_data phase=Idle",
                ),
                PersistedLogsAuditRecord(
                    occurredAtEpochMillis = 12_345,
                    category = LogsAuditRecordCategory.Rotation,
                    severity = LogsAuditRecordSeverity.Info,
                    title = "Rotation copy_diagnostics",
                    detail = "action=copy_diagnostics phase=Idle",
                ),
            ),
            controller
                .consumeEffects()
                .filterIsInstance<RotationScreenEffect.RecordAuditAction>()
                .map(RotationScreenEffect.RecordAuditAction::record),
        )
    }

    @Test
    fun `controller exposes pending unsafe rotation action as visible state until resolved`() {
        var rotationStatus = RotationStatus.idle()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { rotationStatus },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
            )

        controller.handle(RotationScreenEvent.RotateAirplaneMode)

        assertEquals("In progress: Rotate airplane mode", controller.state.pendingOperation)
        assertEquals(setOf(RotationScreenWarning.OperationInProgress), controller.state.warnings)
        assertTrue(controller.state.copyableDiagnostics.contains("Warnings: Rotation operation in progress"))

        rotationStatus =
            RotationStatus(
                state = RotationState.CheckingRoot,
                operation = RotationOperation.AirplaneMode,
            )
        controller.handle(RotationScreenEvent.Refresh)

        assertEquals("In progress: Rotate airplane mode", controller.state.pendingOperation)
        assertEquals(setOf(RotationScreenWarning.RotationInProgress), controller.state.warnings)

        rotationStatus =
            RotationStatus(
                state = RotationState.Failed,
                operation = RotationOperation.AirplaneMode,
                failureReason = RotationFailureReason.RootUnavailable,
            )
        controller.handle(RotationScreenEvent.Refresh)

        assertEquals("None", controller.state.pendingOperation)
    }

    @Test
    fun `controller emits redacted diagnostics copy effect once`() {
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = {
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.20",
                        publicIpChanged = true,
                    )
                },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                secrets = LogRedactionSecrets(proxyCredential = "203.0.113.20"),
            )

        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertTrue(copyText.contains("Current phase: Completed"))
        assertFalse(copyText.contains("203.0.113.20"))
        assertTrue(controller.consumeEffects().isEmpty())
    }

    @Test
    fun `controller refreshes diagnostics redaction secrets from provider`() {
        var secrets = LogRedactionSecrets()
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = {
                    RotationStatus(
                        state = RotationState.Completed,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.20",
                        publicIpChanged = true,
                    )
                },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                secretsProvider = { secrets },
            )

        secrets = LogRedactionSecrets(proxyCredential = "203.0.113.20")
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertFalse(copyText.contains("203.0.113.20"))
        assertTrue(copyText.contains("[REDACTED]"))
    }

    @Test
    fun `controller displays current public ip from provider and redacts it in diagnostics copy`() {
        var publicIp: String? = "203.0.113.44"
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                currentPublicIpProvider = { publicIp },
                secretsProvider = { LogRedactionSecrets(proxyCredential = "198.51.100.55") },
            )

        assertEquals("203.0.113.44", controller.state.currentPublicIp)

        publicIp = "198.51.100.55"
        controller.handle(RotationScreenEvent.Refresh)
        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertEquals("[REDACTED]", controller.state.currentPublicIp)
        assertTrue(copyText.contains("Current public IP: [REDACTED]"))
        assertFalse(copyText.contains("198.51.100.55"))
    }

    @Test
    fun `active cooldown exposes visible rotation warning and copy diagnostics entry`() {
        val state =
            RotationScreenState.from(
                config = rootEnabledConfig(),
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Available,
                cooldownRemainingSeconds = 45,
            )

        assertEquals(setOf(RotationScreenWarning.RotationCooldownActive), state.warnings)
        assertTrue(state.copyableDiagnostics.contains("Warnings: Rotation blocked by cooldown"))
    }

    @Test
    fun `active rotation exposes visible in-progress warning and copy diagnostics entry`() {
        val state =
            RotationScreenState.from(
                config = rootEnabledConfig(),
                rotationStatus =
                    RotationStatus(
                        state = RotationState.CheckingRoot,
                        operation = RotationOperation.MobileData,
                    ),
                rootAvailability = RootAvailabilityStatus.Available,
            )

        assertEquals(setOf(RotationScreenWarning.RotationInProgress), state.warnings)
        assertTrue(state.copyableDiagnostics.contains("Warnings: Rotation already in progress"))
    }

    @Test
    fun `enabled root operations with unavailable root exposes visible warning and copy diagnostics entry`() {
        val state =
            RotationScreenState.from(
                config = rootEnabledConfig(),
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Unavailable,
            )

        assertEquals(setOf(RotationScreenWarning.RootUnavailable), state.warnings)
        assertTrue(state.copyableDiagnostics.contains("Warnings: Root access unavailable"))
    }

    @Test
    fun `disabled root operations do not expose root unavailable warning`() {
        val state =
            RotationScreenState.from(
                config = AppConfig.default(),
                rotationStatus = RotationStatus.idle(),
                rootAvailability = RootAvailabilityStatus.Unavailable,
            )

        assertEquals(emptySet(), state.warnings)
        assertTrue(state.copyableDiagnostics.contains("Warnings: None"))
    }

    @Test
    fun `inactive cooldown does not expose rotation warning`() {
        listOf(null, 0L, -1L).forEach { cooldownRemainingSeconds ->
            val state =
                RotationScreenState.from(
                    config = rootEnabledConfig(),
                    rotationStatus = RotationStatus.idle(),
                    rootAvailability = RootAvailabilityStatus.Available,
                    cooldownRemainingSeconds = cooldownRemainingSeconds,
                )

            assertEquals(emptySet(), state.warnings)
            assertTrue(state.copyableDiagnostics.contains("Warnings: None"))
        }
    }

    @Test
    fun `controller copy diagnostics preserves active cooldown warning`() {
        val controller =
            RotationScreenController(
                configProvider = { rootEnabledConfig() },
                rotationStatusProvider = { RotationStatus.idle() },
                rootAvailabilityProvider = { RootAvailabilityStatus.Available },
                cooldownRemainingSecondsProvider = { 45 },
            )

        controller.handle(RotationScreenEvent.CopyDiagnostics)

        val copyText = (controller.consumeEffects().single() as RotationScreenEffect.CopyText).text
        assertTrue(copyText.contains("Warnings: Rotation blocked by cooldown"))
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
}
