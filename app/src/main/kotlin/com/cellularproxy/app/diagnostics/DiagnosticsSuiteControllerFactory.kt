package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus

object DiagnosticsSuiteControllerFactory {
    fun create(
        config: () -> AppConfig,
        proxyStatus: () -> ProxyServiceStatus,
        observedNetworks: () -> List<NetworkDescriptor>,
        publicIpProbeResult: () -> PublicIpDiagnosticsProbeResult,
        localManagementApiProbeResult: () -> LocalManagementApiProbeResult,
        cloudflareManagementApiProbeResult: () -> CloudflareManagementApiProbeResult,
        nanoTime: () -> Long = System::nanoTime,
    ): DiagnosticsSuiteController = DiagnosticsSuiteController(
        checks =
            mapOf(
                DiagnosticCheckType.RootAvailability to
                    DiagnosticChecks.rootAvailability(
                        rootOperationsEnabled = { config().root.operationsEnabled },
                        rootAvailability = { proxyStatus().rootAvailability },
                    ),
                DiagnosticCheckType.SelectedRoute to
                    DiagnosticChecks.selectedRoute(
                        routeTarget = { config().network.defaultRoutePolicy },
                        observedNetworks = observedNetworks,
                    ),
                DiagnosticCheckType.PublicIp to
                    DiagnosticChecks.publicIp(probeResult = publicIpProbeResult),
                DiagnosticCheckType.ProxyBind to
                    DiagnosticChecks.proxyBind(status = proxyStatus),
                DiagnosticCheckType.LocalManagementApi to
                    DiagnosticChecks.localManagementApi(probeResult = localManagementApiProbeResult),
                DiagnosticCheckType.CloudflareTunnel to
                    DiagnosticChecks.cloudflareTunnel(status = { proxyStatus().cloudflare }),
                DiagnosticCheckType.CloudflareManagementApi to
                    DiagnosticChecks.cloudflareManagementApi(probeResult = cloudflareManagementApiProbeResult),
            ),
        nanoTime = nanoTime,
    )
}
