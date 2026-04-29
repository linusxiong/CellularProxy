package com.cellularproxy.app.service

import com.cellularproxy.app.R
import com.cellularproxy.app.status.NotificationPriority
import com.cellularproxy.app.status.NotificationStatusModel

data class ForegroundServiceNotificationDescriptor(
    val channelId: String,
    val channelNameResId: Int,
    val channelDescriptionResId: Int,
    val channelImportance: NotificationChannelImportance,
    val notificationId: Int,
    val smallIconResId: Int,
    val title: String,
    val contentText: String,
    val detailText: String,
    val warningText: String?,
    val priority: NotificationPriority,
    val isOngoing: Boolean,
    val stopAction: ForegroundServiceNotificationAction?,
) {
    init {
        require(channelId.isNotBlank()) { "Notification channel id must not be blank" }
        require(notificationId > 0) { "Foreground notification id must be positive" }
        require(channelNameResId != 0) { "Foreground notification channel name resource is required" }
        require(channelDescriptionResId != 0) {
            "Foreground notification channel description resource is required"
        }
        require(smallIconResId != 0) { "Foreground notification small icon resource is required" }
        require(title.isNotBlank()) { "Foreground notification title must not be blank" }
        require(contentText.isNotBlank()) { "Foreground notification content text must not be blank" }
        require(detailText.isNotBlank()) { "Foreground notification detail text must not be blank" }
        require(stopAction == null || isOngoing) {
            "Foreground notification stop action requires an ongoing notification"
        }
        require(!isOngoing || priority != NotificationPriority.Status) {
            "Ongoing foreground notification cannot use status priority"
        }
    }

    companion object {
        const val CHANNEL_ID: String = "cellularproxy_proxy_service"
        const val NOTIFICATION_ID: Int = 10_001

        fun from(status: NotificationStatusModel): ForegroundServiceNotificationDescriptor = ForegroundServiceNotificationDescriptor(
            channelId = CHANNEL_ID,
            channelNameResId = R.string.notification_channel_proxy_service,
            channelDescriptionResId = R.string.notification_channel_proxy_service_description,
            channelImportance = NotificationChannelImportance.Low,
            notificationId = NOTIFICATION_ID,
            smallIconResId = R.drawable.ic_stat_cellularproxy,
            title = status.title,
            contentText = status.contentText,
            detailText = status.detailText,
            warningText = status.warningText,
            priority = status.priority,
            isOngoing = status.isOngoing,
            stopAction =
                if (status.stopActionEnabled) {
                    ForegroundServiceNotificationAction(
                        label = STOP_ACTION_LABEL,
                        action = ForegroundServiceActions.STOP_PROXY_FROM_NOTIFICATION,
                        source = ForegroundServiceCommandSource.Notification,
                    )
                } else {
                    null
                },
        )
    }
}

enum class NotificationChannelImportance {
    Low,
}

data class ForegroundServiceNotificationAction(
    val label: String,
    val action: String,
    val source: ForegroundServiceCommandSource,
) {
    init {
        require(label.isNotBlank()) { "Foreground notification action label must not be blank" }
        require(action.isNotBlank()) { "Foreground notification action must not be blank" }
    }
}

private const val STOP_ACTION_LABEL = "Stop"
