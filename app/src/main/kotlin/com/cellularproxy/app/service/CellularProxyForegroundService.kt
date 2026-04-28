package com.cellularproxy.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cellularproxy.app.audit.CellularProxyForegroundServiceAuditStore
import com.cellularproxy.app.status.NotificationStatusModel
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus

class CellularProxyForegroundService : Service() {
    private val runtimeCompositionOwner =
        ForegroundServiceRuntimeCompositionOwner {
            CellularProxyRuntimeCompositionInstaller.install(
                context = this,
                onRuntimeStatusAvailable = ::updateForegroundNotification,
            )
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ForegroundServiceCommandExecutor.execute(
            commandResult = ForegroundServiceCommandParser.parse(intent?.action),
            runtimeLifecycle = runtimeCompositionOwner,
            applyServiceEffect = { effect -> applyServiceEffect(effect, startId) },
            recordAudit = CellularProxyForegroundServiceAuditStore.foregroundServiceAuditLog(this)::record,
            reportAuditFailure = { exception ->
                Log.w(FOREGROUND_SERVICE_AUDIT_LOG_TAG, "Failed to persist foreground service audit record", exception)
            },
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            runtimeCompositionOwner.closeQuietly()
        } finally {
            super.onDestroy()
        }
    }

    private fun applyServiceEffect(
        effect: ForegroundServiceCommandEffect,
        startId: Int,
    ) {
        when (effect) {
            ForegroundServiceCommandEffect.PromoteToForeground -> promoteToForeground()
            ForegroundServiceCommandEffect.StopForegroundAndSelf -> stopForegroundAndSelf(startId)
            ForegroundServiceCommandEffect.StopSelf -> stopSelf(startId)
        }
    }

    private fun promoteToForeground() {
        val descriptor =
            ForegroundServiceNotificationDescriptor.from(
                NotificationStatusModel.from(
                    config = AppConfig.default(),
                    status = ProxyServiceStatus(state = ProxyServiceState.Starting),
                ),
            )
        createNotificationChannel(descriptor)
        val notification = buildNotification(descriptor)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                descriptor.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(descriptor.notificationId, notification)
        }
    }

    private fun updateForegroundNotification(runtimeStatus: NotificationRuntimeStatus) {
        val descriptor =
            ForegroundServiceNotificationDescriptor.from(
                NotificationStatusModel.from(
                    config = runtimeStatus.config,
                    status = runtimeStatus.status,
                ),
            )
        createNotificationChannel(descriptor)
        getSystemService(NotificationManager::class.java)
            .notify(descriptor.notificationId, buildNotification(descriptor))
    }

    private fun stopForegroundAndSelf(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun createNotificationChannel(descriptor: ForegroundServiceNotificationDescriptor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel =
            NotificationChannel(
                descriptor.channelId,
                getString(descriptor.channelNameResId),
                descriptor.channelImportance.toAndroidImportance(),
            ).apply {
                description = getString(descriptor.channelDescriptionResId)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(descriptor: ForegroundServiceNotificationDescriptor): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, descriptor.channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        builder
            .setSmallIcon(descriptor.smallIconResId)
            .setContentTitle(descriptor.title)
            .setContentText(descriptor.contentText)
            .setStyle(
                Notification
                    .BigTextStyle()
                    .bigText(
                        listOfNotNull(
                            descriptor.contentText,
                            descriptor.detailText,
                            descriptor.warningText,
                        ).joinToString("\n"),
                    ),
            ).setOngoing(descriptor.isOngoing)
            .setShowWhen(false)

        descriptor.stopAction?.let { action ->
            builder.addAction(
                Notification.Action
                    .Builder(
                        Icon.createWithResource(this, descriptor.smallIconResId),
                        action.label,
                        stopActionPendingIntent(action),
                    ).build(),
            )
        }

        return builder.build()
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun stopActionPendingIntent(action: ForegroundServiceNotificationAction): PendingIntent {
        val intent =
            Intent(this, CellularProxyForegroundService::class.java)
                .setAction(action.action)
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        return PendingIntent.getService(this, STOP_ACTION_REQUEST_CODE, intent, flags)
    }
}

private fun NotificationChannelImportance.toAndroidImportance(): Int = when (this) {
    NotificationChannelImportance.Low -> NotificationManager.IMPORTANCE_LOW
}

private const val FOREGROUND_SERVICE_AUDIT_LOG_TAG = "CellularProxyFgAudit"
private const val STOP_ACTION_REQUEST_CODE = 1
