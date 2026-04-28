package com.cellularproxy.app.service

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyServiceStatus

data class NotificationRuntimeStatus(
    val config: AppConfig,
    val status: ProxyServiceStatus,
)
