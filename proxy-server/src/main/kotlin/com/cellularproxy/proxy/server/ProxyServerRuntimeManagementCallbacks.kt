package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.management.ManagementApiCallbacks
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationStartGateResult
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlin.time.Duration

object ProxyServerRuntimeManagementCallbacks {
    fun create(
        runtime: RunningProxyServerRuntime,
        networks: () -> List<NetworkDescriptor>,
        publicIp: () -> String?,
        cloudflareStatus: () -> CloudflareTunnelStatus,
        cloudflareStart: () -> CloudflareTunnelTransitionResult,
        cloudflareStop: () -> CloudflareTunnelTransitionResult,
        rotationControlPlane: RotationControlPlane,
        nowElapsedMillis: () -> Long,
        rotationCooldown: Duration,
    ): ManagementApiCallbacks =
        create(
            runtime = runtime,
            networks = networks,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = {
                rotationControlPlane.requestStart(
                    operation = RotationOperation.MobileData,
                    nowElapsedMillis = nowElapsedMillis(),
                    cooldown = rotationCooldown,
                ).toManagementTransition()
            },
            rotateAirplaneMode = {
                rotationControlPlane.requestStart(
                    operation = RotationOperation.AirplaneMode,
                    nowElapsedMillis = nowElapsedMillis(),
                    cooldown = rotationCooldown,
                ).toManagementTransition()
            },
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

private fun RotationStartGateResult.toManagementTransition(): RotationTransitionResult =
    cooldownTransition ?: startTransition
