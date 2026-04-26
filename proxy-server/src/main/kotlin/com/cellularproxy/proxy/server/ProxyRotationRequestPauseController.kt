package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.shared.rotation.RotationEvent
import java.util.concurrent.atomic.AtomicBoolean

class ProxyRotationRequestPauseController(
    private val baseConfig: ProxyIngressPreflightConfig,
) {
    private val paused = AtomicBoolean(baseConfig.proxyRequestsPaused)

    val proxyRequestsPaused: Boolean
        get() = paused.get()

    fun currentConfig(): ProxyIngressPreflightConfig =
        baseConfig.copy(proxyRequestsPaused = paused.get())

    fun pauseProxyRequests(): RotationEvent.NewRequestsPaused {
        paused.set(true)
        return RotationEvent.NewRequestsPaused
    }

    fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed {
        paused.set(false)
        return RotationEvent.ProxyRequestsResumed
    }
}
