package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.management.ManagementApiCallbacks
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationStartGateResult
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
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
        rootOperationsEnabled: () -> Boolean,
    ): ManagementApiCallbacks =
        create(
            runtime = runtime,
            networks = networks,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rootOperationsEnabled = rootOperationsEnabled,
            rotateMobileData = {
                requestRotationIfRootEnabled(
                    operation = RotationOperation.MobileData,
                    rootOperationsEnabled = rootOperationsEnabled,
                    rotationControlPlane = rotationControlPlane,
                    nowElapsedMillis = nowElapsedMillis,
                    rotationCooldown = rotationCooldown,
                )
            },
            rotateAirplaneMode = {
                requestRotationIfRootEnabled(
                    operation = RotationOperation.AirplaneMode,
                    rootOperationsEnabled = rootOperationsEnabled,
                    rotationControlPlane = rotationControlPlane,
                    nowElapsedMillis = nowElapsedMillis,
                    rotationCooldown = rotationCooldown,
                )
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
        rootOperationsEnabled: () -> Boolean,
    ): ManagementApiCallbacks =
        ManagementApiCallbacks(
            healthStatus = { runtime.status },
            status = {
                runtime.status.copy(
                    publicIp = publicIp(),
                    cloudflare = cloudflareStatus(),
                )
            },
            rootOperationsEnabled = rootOperationsEnabled,
            networks = networks,
            publicIp = publicIp,
            cloudflareStatus = cloudflareStatus,
            cloudflareStart = cloudflareStart,
            cloudflareStop = cloudflareStop,
            rotateMobileData = rotateMobileData.guardRootOperations(
                rootOperationsEnabled = rootOperationsEnabled,
                operation = RotationOperation.MobileData,
            ),
            rotateAirplaneMode = rotateAirplaneMode.guardRootOperations(
                rootOperationsEnabled = rootOperationsEnabled,
                operation = RotationOperation.AirplaneMode,
            ),
            serviceStop = { runtime.requestStop() },
        )
}

private fun RotationStartGateResult.toManagementTransition(): RotationTransitionResult =
    cooldownTransition ?: startTransition

private fun requestRotationIfRootEnabled(
    operation: RotationOperation,
    rootOperationsEnabled: () -> Boolean,
    rotationControlPlane: RotationControlPlane,
    nowElapsedMillis: () -> Long,
    rotationCooldown: Duration,
): RotationTransitionResult =
    if (rootOperationsEnabled()) {
        rotationControlPlane.requestStart(
            operation = operation,
            nowElapsedMillis = nowElapsedMillis(),
            cooldown = rotationCooldown,
        ).toManagementTransition()
    } else {
        rootOperationsDisabledRotationTransition(operation)
    }

private fun rootOperationsDisabledRotationTransition(
    operation: RotationOperation,
): RotationTransitionResult =
    RotationTransitionResult(
        disposition = RotationTransitionDisposition.Rejected,
        status = RotationStatus(
            state = RotationState.Failed,
            operation = operation,
            failureReason = RotationFailureReason.RootOperationsDisabled,
        ),
    )

private fun (() -> RotationTransitionResult).guardRootOperations(
    rootOperationsEnabled: () -> Boolean,
    operation: RotationOperation,
): () -> RotationTransitionResult =
    {
        if (rootOperationsEnabled()) {
            this()
        } else {
            rootOperationsDisabledRotationTransition(operation)
        }
    }
