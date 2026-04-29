package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.rotation.RotationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyRotationRequestPauseControllerTest {
    @Test
    fun `pause and resume expose state-machine events and current preflight config snapshots`() {
        val baseConfig = ingressConfig()
        val controller = ProxyRotationRequestPauseController(baseConfig)

        assertFalse(controller.proxyRequestsPaused)
        assertFalse(controller.currentConfig().proxyRequestsPaused)

        assertEquals(RotationEvent.NewRequestsPaused, controller.pauseProxyRequests())
        assertTrue(controller.proxyRequestsPaused)
        assertTrue(controller.currentConfig().proxyRequestsPaused)
        assertFalse(baseConfig.proxyRequestsPaused, "base config should remain immutable")

        assertEquals(RotationEvent.ProxyRequestsResumed, controller.resumeProxyRequests())
        assertFalse(controller.proxyRequestsPaused)
        assertFalse(controller.currentConfig().proxyRequestsPaused)
    }

    private fun ingressConfig(): ProxyIngressPreflightConfig = ProxyIngressPreflightConfig(
        connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 4),
        requestAdmission =
            ProxyRequestAdmissionConfig(
                proxyAuthentication =
                    ProxyAuthenticationConfig(
                        authEnabled = false,
                        credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                    ),
                managementApiToken = "management-token",
            ),
    )
}
