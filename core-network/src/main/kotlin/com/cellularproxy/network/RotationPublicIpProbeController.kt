package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationEvent

fun interface PublicIpProbeRunner {
    suspend fun probe(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): PublicIpProbeResult
}

class RotationPublicIpProbeController(
    private val probeRunner: PublicIpProbeRunner,
) {
    suspend fun probeOldPublicIp(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
    ): RotationPublicIpProbeDecision = when (val result = probeRunner.probe(route, endpoint)) {
        is PublicIpProbeResult.Success ->
            RotationPublicIpProbeDecision.OldProbeSucceeded(result)
        is PublicIpProbeResult.Failed ->
            RotationPublicIpProbeDecision.OldProbeFailed(result)
    }

    suspend fun probeNewPublicIp(
        route: RouteTarget,
        endpoint: PublicIpProbeEndpoint,
        strictIpChangeRequired: Boolean,
    ): RotationPublicIpProbeDecision = when (val result = probeRunner.probe(route, endpoint)) {
        is PublicIpProbeResult.Success ->
            RotationPublicIpProbeDecision.NewProbeSucceeded(
                result = result,
                strictIpChangeRequired = strictIpChangeRequired,
            )
        is PublicIpProbeResult.Failed ->
            RotationPublicIpProbeDecision.NewProbeFailed(result)
    }
}

sealed interface RotationPublicIpProbeDecision {
    val event: RotationEvent

    data class OldProbeSucceeded(
        val result: PublicIpProbeResult.Success,
    ) : RotationPublicIpProbeDecision {
        val publicIp: String
            get() = result.publicIp

        val network: NetworkDescriptor
            get() = result.network

        override val event: RotationEvent =
            RotationEvent.OldPublicIpProbeSucceeded(publicIp)
    }

    data class OldProbeFailed(
        val result: PublicIpProbeResult.Failed,
    ) : RotationPublicIpProbeDecision {
        val reason: PublicIpProbeFailure
            get() = result.reason

        val network: NetworkDescriptor?
            get() = result.network

        override val event: RotationEvent = RotationEvent.OldPublicIpProbeFailed
    }

    data class NewProbeSucceeded(
        val result: PublicIpProbeResult.Success,
        val strictIpChangeRequired: Boolean,
    ) : RotationPublicIpProbeDecision {
        val publicIp: String
            get() = result.publicIp

        val network: NetworkDescriptor
            get() = result.network

        override val event: RotationEvent =
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = publicIp,
                strictIpChangeRequired = strictIpChangeRequired,
            )
    }

    data class NewProbeFailed(
        val result: PublicIpProbeResult.Failed,
    ) : RotationPublicIpProbeDecision {
        val reason: PublicIpProbeFailure
            get() = result.reason

        val network: NetworkDescriptor?
            get() = result.network

        override val event: RotationEvent = RotationEvent.NewPublicIpProbeFailed
    }
}
