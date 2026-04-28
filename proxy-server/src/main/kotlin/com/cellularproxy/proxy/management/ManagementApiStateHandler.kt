package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionResult

data class ManagementApiCallbacks(
    val healthStatus: () -> ProxyServiceStatus,
    val status: () -> ProxyServiceStatus,
    val networks: () -> List<NetworkDescriptor>,
    val publicIp: () -> String?,
    val cloudflareStatus: () -> CloudflareTunnelStatus,
    val cloudflareStart: () -> CloudflareTunnelTransitionResult,
    val cloudflareStop: () -> CloudflareTunnelTransitionResult,
    val cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
    val rotateMobileData: () -> RotationTransitionResult,
    val rotateAirplaneMode: () -> RotationTransitionResult,
    val rotationStatus: () -> RotationStatus = { RotationStatus.idle() },
    val rotationCooldownRemainingMillis: () -> Long? = { null },
    val cloudflareEdgeSessionSummary: () -> String? = { null },
    val serviceStop: () -> ProxyServiceStopTransitionResult,
    val serviceRestart: () -> ManagementApiServiceRestartResult = {
        ManagementApiServiceRestartResult.rejected(
            ManagementApiServiceRestartFailureReason.ExecutionUnavailable,
        )
    },
    val rootOperationsEnabled: () -> Boolean,
)

class ManagementApiStateHandler(
    private val callbacks: ManagementApiCallbacks,
    private val secrets: LogRedactionSecrets = LogRedactionSecrets(),
) : ManagementApiHandler {
    override fun handle(operation: ManagementApiOperation): ManagementApiResponse = when (operation) {
        ManagementApiOperation.Health ->
            ManagementApiReadOnlyResponses.health(callbacks.healthStatus())
        ManagementApiOperation.Status ->
            renderStatus()
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
        ManagementApiOperation.CloudflareReconnect ->
            ManagementApiCloudflareActionResponses.transition(callbacks.cloudflareReconnect(), secrets)
        ManagementApiOperation.RotateMobileData ->
            ManagementApiRotationActionResponses.transition(callbacks.rotateMobileData())
        ManagementApiOperation.RotateAirplaneMode ->
            ManagementApiRotationActionResponses.transition(callbacks.rotateAirplaneMode())
        ManagementApiOperation.ServiceStop ->
            ManagementApiServiceStopActionResponses.transition(callbacks.serviceStop())
        ManagementApiOperation.ServiceRestart ->
            ManagementApiServiceRestartActionResponses.transition(callbacks.serviceRestart())
    }

    private fun renderStatus(): ManagementApiResponse {
        val rootOperationsEnabled = callbacks.rootOperationsEnabled()
        val status =
            callbacks.status().let { currentStatus ->
                if (rootOperationsEnabled) {
                    currentStatus
                } else {
                    currentStatus.copy(rootAvailability = RootAvailabilityStatus.Unknown)
                }
            }
        return ManagementApiReadOnlyResponses.status(
            status = status,
            rotationStatus = callbacks.rotationStatus(),
            rotationCooldownRemainingMillis = callbacks.rotationCooldownRemainingMillis(),
            cloudflareEdgeSessionSummary = callbacks.cloudflareEdgeSessionSummary(),
            secrets = secrets,
            rootOperationsEnabled = rootOperationsEnabled,
        )
    }
}

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = CloudflareTunnelStatus.disabled(),
)
