package com.cellularproxy.network

import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.rotation.RotationEvent
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationPublicIpProbeControllerTest {
    @Test
    fun `old public IP probe success maps to old probe succeeded rotation event`() {
        val network = network("cell", NetworkCategory.Cellular)
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val runner = RecordingPublicIpProbeRunner(
            PublicIpProbeResult.Success(
                publicIp = "203.0.113.10",
                network = network,
            ),
        )

        val decision = runSuspend {
            RotationPublicIpProbeController(runner).probeOldPublicIp(
                route = RouteTarget.Cellular,
                endpoint = endpoint,
            )
        }

        val succeeded = assertIs<RotationPublicIpProbeDecision.OldProbeSucceeded>(decision)
        assertEquals("203.0.113.10", succeeded.publicIp)
        assertEquals(network, succeeded.network)
        assertEquals(RotationEvent.OldPublicIpProbeSucceeded("203.0.113.10"), succeeded.event)
        assertEquals(listOf(PublicIpProbeCall(RouteTarget.Cellular, endpoint)), runner.calls)
    }

    @Test
    fun `old public IP probe failure maps to old probe failed rotation event and preserves failure metadata`() {
        val network = network("wifi", NetworkCategory.WiFi)
        val endpoint = PublicIpProbeEndpoint(host = "ip.example")
        val runner = RecordingPublicIpProbeRunner(
            PublicIpProbeResult.Failed(
                reason = PublicIpProbeFailure.ResponseTimedOut,
                network = network,
            ),
        )

        val decision = runSuspend {
            RotationPublicIpProbeController(runner).probeOldPublicIp(
                route = RouteTarget.WiFi,
                endpoint = endpoint,
            )
        }

        val failed = assertIs<RotationPublicIpProbeDecision.OldProbeFailed>(decision)
        assertEquals(PublicIpProbeFailure.ResponseTimedOut, failed.reason)
        assertEquals(network, failed.network)
        assertEquals(RotationEvent.OldPublicIpProbeFailed, failed.event)
    }

    @Test
    fun `new public IP probe success maps strict flag into new probe succeeded rotation event`() {
        val network = network("cell", NetworkCategory.Cellular)
        val runner = RecordingPublicIpProbeRunner(
            PublicIpProbeResult.Success(
                publicIp = "203.0.113.11",
                network = network,
            ),
        )

        val decision = runSuspend {
            RotationPublicIpProbeController(runner).probeNewPublicIp(
                route = RouteTarget.Cellular,
                endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                strictIpChangeRequired = true,
            )
        }

        val succeeded = assertIs<RotationPublicIpProbeDecision.NewProbeSucceeded>(decision)
        assertEquals("203.0.113.11", succeeded.publicIp)
        assertEquals(network, succeeded.network)
        assertEquals(
            RotationEvent.NewPublicIpProbeSucceeded(
                publicIp = "203.0.113.11",
                strictIpChangeRequired = true,
            ),
            succeeded.event,
        )
    }

    @Test
    fun `new public IP probe failure maps to new probe failed rotation event`() {
        val runner = RecordingPublicIpProbeRunner(
            PublicIpProbeResult.Failed(PublicIpProbeFailure.InvalidPublicIp),
        )

        val decision = runSuspend {
            RotationPublicIpProbeController(runner).probeNewPublicIp(
                route = RouteTarget.Cellular,
                endpoint = PublicIpProbeEndpoint(host = "ip.example"),
                strictIpChangeRequired = false,
            )
        }

        val failed = assertIs<RotationPublicIpProbeDecision.NewProbeFailed>(decision)
        assertEquals(PublicIpProbeFailure.InvalidPublicIp, failed.reason)
        assertEquals(null, failed.network)
        assertEquals(RotationEvent.NewPublicIpProbeFailed, failed.event)
    }

    private class RecordingPublicIpProbeRunner(
        private val result: PublicIpProbeResult,
    ) : PublicIpProbeRunner {
        val calls = mutableListOf<PublicIpProbeCall>()

        override suspend fun probe(
            route: RouteTarget,
            endpoint: PublicIpProbeEndpoint,
        ): PublicIpProbeResult {
            calls += PublicIpProbeCall(route, endpoint)
            return result
        }
    }

    private data class PublicIpProbeCall(
        val route: RouteTarget,
        val endpoint: PublicIpProbeEndpoint,
    )

    private fun network(
        id: String,
        category: NetworkCategory,
    ): NetworkDescriptor =
        NetworkDescriptor(
            id = id,
            category = category,
            displayName = id,
            isAvailable = true,
        )
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { completed ->
            result = completed
        },
    )
    return result!!.getOrThrow()
}
