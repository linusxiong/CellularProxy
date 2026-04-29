package com.cellularproxy.app.diagnostics

import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError

class ProxyBindDiagnosticsProbe(
    private val config: () -> ProxyConfig,
    private val bindListener: (listenHost: String, listenPort: Int) -> ProxyServerSocketBindResult =
        { listenHost, listenPort -> ProxyServerSocketBinder.bind(listenHost, listenPort) },
) {
    fun probe(proxyStatus: ProxyServiceStatus): ProxyBindDiagnosticsProbeResult = if (proxyStatus.state == ProxyServiceState.Running) {
        ProxyBindDiagnosticsProbeResult.fromStatus(proxyStatus)
    } else {
        probe()
    }

    fun probe(): ProxyBindDiagnosticsProbeResult {
        val proxyConfig = config()
        return when (val result = bindListener(proxyConfig.listenHost, proxyConfig.listenPort)) {
            is ProxyServerSocketBindResult.Bound ->
                result.listener.use { listener ->
                    ProxyBindDiagnosticsProbeResult.Bound(
                        listenHost = listener.listenHost,
                        listenPort = listener.listenPort,
                    )
                }
            is ProxyServerSocketBindResult.Failed ->
                when (val startupError = result.startupError) {
                    ProxyStartupError.PortAlreadyInUse -> ProxyBindDiagnosticsProbeResult.BindFailed
                    else -> ProxyBindDiagnosticsProbeResult.StartupBlocked(startupError)
                }
        }
    }
}
