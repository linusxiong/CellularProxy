package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult
import com.cellularproxy.shared.rotation.RotationTransitionResult

data class ManagementApiCallbacks(
    val healthStatus: () -> ProxyServiceStatus,
    val status: () -> ProxyServiceStatus,
    val networks: () -> List<NetworkDescriptor>,
    val publicIp: () -> String?,
    val cloudflareStatus: () -> CloudflareTunnelStatus,
    val cloudflareStart: () -> CloudflareTunnelTransitionResult,
    val cloudflareStop: () -> CloudflareTunnelTransitionResult,
    val rotateMobileData: () -> RotationTransitionResult,
    val rotateAirplaneMode: () -> RotationTransitionResult,
    val serviceStop: () -> ProxyServiceStopTransitionResult,
    val rootOperationsEnabled: () -> Boolean,
)

class ManagementApiStateHandler(
    private val callbacks: ManagementApiCallbacks,
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
) : ManagementApiHandler {
    override fun handle(operation: ManagementApiOperation): ManagementApiResponse =
        when (operation) {
            ManagementApiOperation.Health ->
                ManagementApiReadOnlyResponses.health(callbacks.healthStatus())
            ManagementApiOperation.Status ->
                ManagementApiReadOnlyResponses.status(
                    status = callbacks.status(),
                    secrets = secrets,
                    rootOperationsEnabled = callbacks.rootOperationsEnabled(),
                )
            ManagementApiOperation.Networks ->
                ManagementApiReadOnlyResponses.networks(callbacks.networks())
            ManagementApiOperation.PublicIp ->
                ManagementApiReadOnlyResponses.publicIp(callbacks.publicIp())
            ManagementApiOperation.CloudflareStatus ->
                ManagementApiReadOnlyResponses.cloudflareStatus(callbacks.cloudflareStatus(), secrets)
            ManagementApiOperation.CloudflareStart ->
                ManagementApiCloudflareActionResponses.transition(callbacks.cloudflareStart(), secrets)
            ManagementApiOperation.CloudflareStop ->
                ManagementApiCloudflareActionResponses.transition(callbacks.cloudflareStop(), secrets)
            ManagementApiOperation.RotateMobileData ->
                ManagementApiRotationActionResponses.transition(callbacks.rotateMobileData())
            ManagementApiOperation.RotateAirplaneMode ->
                ManagementApiRotationActionResponses.transition(callbacks.rotateAirplaneMode())
            ManagementApiOperation.ServiceStop ->
                ManagementApiServiceStopActionResponses.transition(callbacks.serviceStop())
        }
}
