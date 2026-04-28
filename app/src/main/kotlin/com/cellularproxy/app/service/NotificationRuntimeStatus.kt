package com.cellularproxy.app.service

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.rotation.RotationStatus

data class NotificationRuntimeStatus(
    val config: AppConfig,
    val status: ProxyServiceStatus,
    val rotationStatus: RotationStatus = RotationStatus.idle(),
    val rotationCooldownRemainingSeconds: Long? = null,
)
