package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.management.ManagementApiCallbacks
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationSessionController
import com.cellularproxy.shared.rotation.RotationTransitionResult

object ProxyServerRuntimeManagementCallbacks {
    fun create(
        runtime: RunningProxyServerRuntime,
        networks: () -> List<NetworkDescriptor>,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        rotationSession: RotationSessionController,
    ): ManagementApiCallbacks =
        create(
            runtime = runtime,
            networks = networks,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = { rotationSession.requestStart(RotationOperation.MobileData) },
            rotateAirplaneMode = { rotationSession.requestStart(RotationOperation.AirplaneMode) },
        )

    fun create(
        runtime: RunningProxyServerRuntime,
        networks: () -> List<NetworkDescriptor>,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
    ): ManagementApiCallbacks =
        ManagementApiCallbacks(
            healthStatus = { runtime.status },
            status = {
                runtime.status.copy(
                    publicIp = publicIp(),
                    cloudflare = cloudflareStatus(),
                )
            },
            networks = networks,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = rotateMobileData,
            rotateAirplaneMode = rotateAirplaneMode,
            serviceStop = { runtime.requestStop() },
        )
}
