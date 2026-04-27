package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig

object ProxyRuntimeIngressConfigFactory {
    fun from(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        maxConcurrentConnections: Int = plainConfig.proxy.maxConcurrentConnections,
    ): ProxyIngressPreflightConfig =
        ProxyIngressPreflightConfig(
            connectionLimit =
                ConnectionLimitAdmissionConfig(
                    maxConcurrentConnections = maxConcurrentConnections,
                ),
            requestAdmission =
                ProxyRequestAdmissionConfig(
                    proxyAuthentication =
                        ProxyAuthenticationConfig(
                            authEnabled = plainConfig.proxy.authEnabled,
                            credential = sensitiveConfig.proxyCredential,
                        ),
                    managementApiToken = sensitiveConfig.managementApiToken,
                ),
        )
}

internal const val DEFAULT_MAX_CONCURRENT_CONNECTIONS: Int = 64
