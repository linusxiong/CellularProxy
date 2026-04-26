package com.cellularproxy.app.service

import com.cellularproxy.app.R
import com.cellularproxy.app.status.NotificationPriority
import com.cellularproxy.app.status.NotificationServiceState
import com.cellularproxy.app.status.NotificationStatusModel
import com.cellularproxy.app.status.NotificationWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundServiceNotificationDescriptorTest {
    @Test
    fun `running status renders resource-backed foreground notification metadata and notification stop action`() {
        val descriptor = ForegroundServiceNotificationDescriptor.from(
            NotificationStatusModel(
                serviceState = NotificationServiceState.Running,
                title = "CellularProxy running",
                contentText = "0.0.0.0:8080 | Cellular | 3 active",
                detailText = "IP 203.0.113.42 | Cloudflare connected | Root unavailable",
                warningText = null,
                warnings = emptySet(),
                priority = NotificationPriority.Foreground,
                isOngoing = true,
                stopActionEnabled = true,
            ),
        )

        assertEquals(ForegroundServiceNotificationDescriptor.CHANNEL_ID, descriptor.channelId)
        assertEquals(R.string.notification_channel_proxy_service, descriptor.channelNameResId)
        assertEquals(
            R.string.notification_channel_proxy_service_description,
            descriptor.channelDescriptionResId,
        )
        assertEquals(NotificationChannelImportance.Low, descriptor.channelImportance)
        assertEquals(ForegroundServiceNotificationDescriptor.NOTIFICATION_ID, descriptor.notificationId)
        assertEquals(R.drawable.ic_stat_cellularproxy, descriptor.smallIconResId)
        assertEquals("CellularProxy running", descriptor.title)
        assertEquals("0.0.0.0:8080 | Cellular | 3 active", descriptor.contentText)
        assertEquals("IP 203.0.113.42 | Cloudflare connected | Root unavailable", descriptor.detailText)
        assertEquals(NotificationPriority.Foreground, descriptor.priority)
        assertTrue(descriptor.isOngoing, "Running notification descriptor should be ongoing")

        val stopAction = assertNotNull(descriptor.stopAction)
        assertEquals("Stop", stopAction.label)
        assertEquals(ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION, stopAction.action)
        assertEquals(ForegroundServiceCommandSource.Notification, stopAction.source)
    }

    @Test
    fun `stopped status omits stop action and keeps status notification metadata`() {
        val descriptor = ForegroundServiceNotificationDescriptor.from(
            NotificationStatusModel(
                serviceState = NotificationServiceState.Stopped,
                title = "CellularProxy stopped",
                contentText = "0.0.0.0:8080 | Automatic | 0 active",
                detailText = "IP unknown | Cloudflare disabled | Root unknown",
                warningText = null,
                warnings = emptySet(),
                priority = NotificationPriority.Status,
                isOngoing = false,
                stopActionEnabled = false,
            ),
        )

        assertEquals(NotificationPriority.Status, descriptor.priority)
        assertFalse(descriptor.isOngoing, "Stopped notification descriptor should not be ongoing")
        assertNull(descriptor.stopAction)
    }

    @Test
    fun `warning text is preserved without requiring warning details in the stop action`() {
        val descriptor = ForegroundServiceNotificationDescriptor.from(
            NotificationStatusModel(
                serviceState = NotificationServiceState.Failed,
                title = "CellularProxy failed",
                contentText = "0.0.0.0:8080 | Automatic | 0 active",
                detailText = "IP unknown | Cloudflare failed | Root unknown",
                warningText = "Service startup failed | Cloudflare tunnel failed",
                warnings = setOf(NotificationWarning.StartupFailed, NotificationWarning.CloudflareFailed),
                priority = NotificationPriority.Warning,
                isOngoing = false,
                stopActionEnabled = false,
            ),
        )

        assertEquals("Service startup failed | Cloudflare tunnel failed", descriptor.warningText)
        assertEquals(NotificationPriority.Warning, descriptor.priority)
        assertNull(descriptor.stopAction)
    }

    @Test
    fun `descriptor invariants reject foreground stop action on non-ongoing notification`() {
        assertFailsWith<IllegalArgumentException> {
            foregroundDescriptor(
                isOngoing = false,
                stopAction = ForegroundServiceNotificationAction(
                    label = "Stop",
                    action = ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION,
                    source = ForegroundServiceCommandSource.Notification,
                ),
            )
        }
    }

    @Test
    fun `descriptor invariants reject ongoing notification with status priority`() {
        assertFailsWith<IllegalArgumentException> {
            foregroundDescriptor(
                priority = NotificationPriority.Status,
                isOngoing = true,
            )
        }
    }

    private fun foregroundDescriptor(
        priority: NotificationPriority = NotificationPriority.Foreground,
        isOngoing: Boolean = true,
        stopAction: ForegroundServiceNotificationAction? = null,
    ): ForegroundServiceNotificationDescriptor = ForegroundServiceNotificationDescriptor(
        channelId = ForegroundServiceNotificationDescriptor.CHANNEL_ID,
        channelNameResId = R.string.notification_channel_proxy_service,
        channelDescriptionResId = R.string.notification_channel_proxy_service_description,
        channelImportance = NotificationChannelImportance.Low,
        notificationId = ForegroundServiceNotificationDescriptor.NOTIFICATION_ID,
        smallIconResId = R.drawable.ic_stat_cellularproxy,
        title = "CellularProxy running",
        contentText = "0.0.0.0:8080 | Cellular | 1 active",
        detailText = "IP unknown | Cloudflare disabled | Root unknown",
        warningText = null,
        priority = priority,
        isOngoing = isOngoing,
        stopAction = stopAction,
    )
}
