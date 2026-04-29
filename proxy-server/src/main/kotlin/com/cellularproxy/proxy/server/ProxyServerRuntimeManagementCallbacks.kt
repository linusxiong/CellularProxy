package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.management.ManagementApiCallbacks
import com.cellularproxy.proxy.management.ManagementApiServiceRestartFailureReason
import com.cellularproxy.proxy.management.ManagementApiServiceRestartResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationCooldownDecision
import com.cellularproxy.shared.rotation.RotationCooldownPolicy
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
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotationControlPlane: RotationControlPlane,
        nowElapsedMillis: () -> Long,
        rotationCooldown: Duration,
        rootOperationsEnabled: () -> Boolean,
        rootAvailability: () -> RootAvailabilityStatus,
    ): ManagementApiCallbacks = create(
        runtime = runtime,
        networks = networks,
        publicIp = publicIp,
        cloudflareStatus = cloudflareStatus,
        cloudflareStart = cloudflareStart,
        cloudflareStop = cloudflareStop,
        cloudflareReconnect = cloudflareReconnect,
        rootOperationsEnabled = rootOperationsEnabled,
        rootAvailability = rootAvailability,
        rotationStatus = { rotationControlPlane.currentStatus },
        rotationCooldownRemainingMillis = {
            rotationCooldownRemainingMillis(
                rotationControlPlane = rotationControlPlane,
                nowElapsedMillis = nowElapsedMillis,
                rotationCooldown = rotationCooldown,
            )
        },
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
        cloudflareReconnect: () -> CloudflareTunnelTransitionResult = ::ignoredCloudflareTransition,
        rotateMobileData: () -> RotationTransitionResult,
        rotateAirplaneMode: () -> RotationTransitionResult,
        serviceRestart: () -> ManagementApiServiceRestartResult = ::unavailableServiceRestart,
        rotationStatus: () -> RotationStatus = { RotationStatus.idle() },
        rotationCooldownRemainingMillis: () -> Long? = { null },
        cloudflareEdgeSessionSummary: () -> String? = { null },
        rootOperationsEnabled: () -> Boolean,
        rootAvailability: () -> RootAvailabilityStatus,
    ): ManagementApiCallbacks = ManagementApiCallbacks(
        healthStatus = { runtime.status },
        status = {
            val rootOperationsAreEnabled = rootOperationsEnabled()
            runtime.status.copy(
                publicIp = publicIp(),
                cloudflare = cloudflareStatus(),
                rootAvailability =
                    if (rootOperationsAreEnabled) {
                        rootAvailability()
                    } else {
                        RootAvailabilityStatus.Unknown
                    },
            )
        },
        rootOperationsEnabled = rootOperationsEnabled,
        networks = networks,
        publicIp = publicIp,
        cloudflareStatus = cloudflareStatus,
        cloudflareStart = cloudflareStart,
        cloudflareStop = cloudflareStop,
        cloudflareReconnect = cloudflareReconnect,
        rotateMobileData =
            rotateMobileData.guardRootOperations(
                rootOperationsEnabled = rootOperationsEnabled,
                operation = RotationOperation.MobileData,
            ),
        rotateAirplaneMode =
            rotateAirplaneMode.guardRootOperations(
                rootOperationsEnabled = rootOperationsEnabled,
                operation = RotationOperation.AirplaneMode,
            ),
        rotationStatus = rotationStatus,
        rotationCooldownRemainingMillis = rotationCooldownRemainingMillis,
        cloudflareEdgeSessionSummary = cloudflareEdgeSessionSummary,
        serviceStop = { runtime.requestStop() },
        serviceRestart = serviceRestart.guardRootOperations(rootOperationsEnabled),
    )
}

private fun rotationCooldownRemainingMillis(
    rotationControlPlane: RotationControlPlane,
    nowElapsedMillis: () -> Long,
    rotationCooldown: Duration,
): Long? = when (
    val decision =
        RotationCooldownPolicy.evaluate(
            lastTerminalRotationElapsedMillis = rotationControlPlane.lastTerminalElapsedMillis,
            nowElapsedMillis = nowElapsedMillis(),
            cooldown = rotationCooldown,
        )
) {
    RotationCooldownDecision.Passed -> null
    is RotationCooldownDecision.Rejected -> decision.remainingCooldown.inWholeMilliseconds
}

private fun RotationStartGateResult.toManagementTransition(): RotationTransitionResult = cooldownTransition ?: startTransition

private fun requestRotationIfRootEnabled(
    operation: RotationOperation,
    rootOperationsEnabled: () -> Boolean,
    rotationControlPlane: RotationControlPlane,
    nowElapsedMillis: () -> Long,
    rotationCooldown: Duration,
): RotationTransitionResult = if (rootOperationsEnabled()) {
    rotationControlPlane
        .requestStart(
            operation = operation,
            nowElapsedMillis = nowElapsedMillis(),
            cooldown = rotationCooldown,
        ).toManagementTransition()
} else {
    rootOperationsDisabledRotationTransition(operation)
}

private fun rootOperationsDisabledRotationTransition(operation: RotationOperation): RotationTransitionResult = RotationTransitionResult(
    disposition = RotationTransitionDisposition.Rejected,
    status =
        RotationStatus(
            state = RotationState.Failed,
            operation = operation,
            failureReason = RotationFailureReason.RootOperationsDisabled,
        ),
)

private fun ignoredCloudflareTransition(): CloudflareTunnelTransitionResult = CloudflareTunnelTransitionResult(
    disposition = CloudflareTunnelTransitionDisposition.Ignored,
    status = CloudflareTunnelStatus.disabled(),
)

private fun (() -> RotationTransitionResult).guardRootOperations(
    rootOperationsEnabled: () -> Boolean,
    operation: RotationOperation,
): () -> RotationTransitionResult = {
    if (rootOperationsEnabled()) {
        this()
    } else {
        rootOperationsDisabledRotationTransition(operation)
    }
}

private fun (() -> ManagementApiServiceRestartResult).guardRootOperations(
    rootOperationsEnabled: () -> Boolean,
): () -> ManagementApiServiceRestartResult = {
    if (rootOperationsEnabled()) {
        this()
    } else {
        ManagementApiServiceRestartResult.rejected(
            ManagementApiServiceRestartFailureReason.RootOperationsDisabled,
        )
    }
}

private fun unavailableServiceRestart(): ManagementApiServiceRestartResult = ManagementApiServiceRestartResult.rejected(
    ManagementApiServiceRestartFailureReason.ExecutionUnavailable,
)
