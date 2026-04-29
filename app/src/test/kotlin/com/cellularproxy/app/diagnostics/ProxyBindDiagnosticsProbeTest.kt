package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProxyBindDiagnosticsProbeTest {
    @Test
    fun `preflight bind succeeds and releases configured listener immediately`() {
        val listenPort = freeLoopbackPort()
        val probe =
            ProxyBindDiagnosticsProbe(
                config = {
                    ProxyConfig(
                        listenHost = LOOPBACK_HOST,
                        listenPort = listenPort,
                    )
                },
            )

        val result = assertIs<ProxyBindDiagnosticsProbeResult.Bound>(probe.probe())

        assertEquals(LOOPBACK_HOST, result.listenHost)
        assertEquals(listenPort, result.listenPort)
        ServerSocket(result.listenPort, 1, InetAddress.getByName(LOOPBACK_HOST)).use {
            assertEquals(result.listenPort, it.localPort)
        }
    }

    @Test
    fun `probe preserves running proxy status without binding occupied listener`() {
        var bindAttempts = 0
        val probe =
            ProxyBindDiagnosticsProbe(
                config = { ProxyConfig(listenHost = LOOPBACK_HOST, listenPort = 8080) },
                bindListener = { _, _ ->
                    bindAttempts += 1
                    error("running proxy should not run a bind preflight")
                },
            )

        val result =
            probe.probe(
                ProxyServiceStatus.running(
                    listenHost = LOOPBACK_HOST,
                    listenPort = 8080,
                    configuredRoute = RouteTarget.Automatic,
                    boundRoute = null,
                    publicIp = null,
                    hasHighSecurityRisk = false,
                ),
            )

        assertEquals(ProxyBindDiagnosticsProbeResult.Bound(LOOPBACK_HOST, 8080), result)
        assertEquals(0, bindAttempts)
    }

    @Test
    fun `preflight bind maps occupied configured port to bind failure`() {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).use { occupied ->
            val probe =
                ProxyBindDiagnosticsProbe(
                    config = {
                        ProxyConfig(
                            listenHost = LOOPBACK_HOST,
                            listenPort = occupied.localPort,
                        )
                    },
                )

            assertEquals(ProxyBindDiagnosticsProbeResult.BindFailed, probe.probe())
        }
    }

    @Test
    fun `preflight bind maps invalid configured address to startup blocked`() {
        val probe = ProxyBindDiagnosticsProbe(config = { ProxyConfig(listenHost = "localhost", listenPort = 8080) })

        assertEquals(
            ProxyBindDiagnosticsProbeResult.StartupBlocked(ProxyStartupError.InvalidListenAddress),
            probe.probe(),
        )
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).use { it.localPort }
    }
}
