package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.shared.rotation.RotationEvent
import java.util.concurrent.atomic.AtomicBoolean

interface ProxyRotationPauseActions {
    fun pauseProxyRequests(): RotationEvent.NewRequestsPaused

    fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed
}

class ProxyRotationRequestPauseController(
    private val baseConfig: ProxyIngressPreflightConfig,
) : ProxyRotationPauseActions {
    private val paused = AtomicBoolean(baseConfig.proxyRequestsPaused)

    val proxyRequestsPaused: Boolean
        get() = paused.get()

    fun currentConfig(): ProxyIngressPreflightConfig = baseConfig.copy(proxyRequestsPaused = paused.get())

    override fun pauseProxyRequests(): RotationEvent.NewRequestsPaused {
        paused.set(true)
        return RotationEvent.NewRequestsPaused
    }

    override fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed {
        paused.set(false)
        return RotationEvent.ProxyRequestsResumed
    }
}
