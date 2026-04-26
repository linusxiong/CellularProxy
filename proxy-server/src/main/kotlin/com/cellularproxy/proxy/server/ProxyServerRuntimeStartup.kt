package com.cellularproxy.proxy.server

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStartupDecision
import com.cellularproxy.shared.proxy.ProxyServiceStartupPolicy
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyStartupError

sealed interface ProxyServerRuntimeStartupResult {
    data class Started(
        val listener: BoundProxyServerSocket,
        val status: ProxyServiceStatus,
    ) : ProxyServerRuntimeStartupResult {
        init {
            require(status.state == ProxyServiceState.Running) {
                "Started runtime requires a running service status"
            }
            require(status.listenHost == listener.listenHost) {
                "Started runtime status must match the bound listener host"
            }
            require(status.listenPort == listener.listenPort) {
                "Started runtime status must match the bound listener port"
            }
        }
    }

    data class Failed(
        val startupError: ProxyStartupError,
        val status: ProxyServiceStatus,
    ) : ProxyServerRuntimeStartupResult {
        init {
            require(status.startupError == startupError) {
                "Failed runtime status must carry the same startup error"
            }
        }
    }
}

object ProxyServerRuntimeStartup {
    fun start(
        config: AppConfig,
        managementApiTokenPresent: Boolean,
        observedNetworks: List<NetworkDescriptor>,
        backlog: Int = DEFAULT_RUNTIME_STARTUP_BACKLOG,
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult =
            ProxyServerSocketBinder::bind,
    ): ProxyServerRuntimeStartupResult {
        require(backlog > 0) { "Server socket backlog must be positive" }

        return when (
            val startup = ProxyServiceStartupPolicy.evaluate(
                config = config,
                managementApiTokenPresent = managementApiTokenPresent,
                observedNetworks = observedNetworks,
            )
        ) {
            is ProxyServiceStartupDecision.Failed ->
                ProxyServerRuntimeStartupResult.Failed(
                    startupError = startup.startupError,
                    status = startup.status,
                )

            is ProxyServiceStartupDecision.Ready ->
                bindReadyStartup(
                    startup = startup,
                    backlog = backlog,
                    bindListener = bindListener,
                )
        }
    }

    private fun bindReadyStartup(
        startup: ProxyServiceStartupDecision.Ready,
        backlog: Int,
        bindListener: (listenHost: String, listenPort: Int, backlog: Int) -> ProxyServerSocketBindResult,
    ): ProxyServerRuntimeStartupResult =
        when (
            val bindResult = bindListener(
                startup.listenHost,
                startup.listenPort,
                backlog,
            )
        ) {
            is ProxyServerSocketBindResult.Failed ->
                ProxyServerRuntimeStartupResult.Failed(
                    startupError = bindResult.startupError,
                    status = ProxyServiceStatus.failed(
                        startupError = bindResult.startupError,
                        configuredRoute = startup.configuredRoute,
                    ),
                )

            is ProxyServerSocketBindResult.Bound ->
                ProxyServerRuntimeStartupResult.Started(
                    listener = bindResult.listener,
                    status = ProxyServiceStatus.running(
                        listenHost = bindResult.listener.listenHost,
                        listenPort = bindResult.listener.listenPort,
                        configuredRoute = startup.configuredRoute,
                        boundRoute = startup.routeCandidates.first(),
                        publicIp = null,
                        hasHighSecurityRisk = startup.hasHighSecurityRisk,
                    ),
                )
        }
}

private const val DEFAULT_RUNTIME_STARTUP_BACKLOG = 50
