package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelStartAndConnectCoordinatorTest {
    @Test
    fun `valid enabled startup token starts tunnel and attempts edge connection`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val connection = TrackableEdgeConnection()
        var receivedCredentials: CloudflareTunnelCredentials? = null
        val coordinator =
            CloudflareTunnelStartAndConnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector { credentials ->
                        receivedCredentials = credentials
                        CloudflareTunnelEdgeConnectionResult.Connected(connection)
                    },
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStartAndConnectCoordinatorResult.ConnectionAttempted>(
                coordinator.startAndConnectIfEnabled(
                    enabled = true,
                    rawTunnelToken = validToken(),
                ),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.startTransition.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), result.startTransition.status)
        val connectionResult = assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(result.connectionResult)
        assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(connectionResult.connectionResult)
        assertEquals(CloudflareTunnelStatus.connected(), connectionResult.transition.status)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
        assertEquals("account-tag", receivedCredentials?.accountTag)
        assertSame(connection, registry.currentSessionOrNull()?.connection)
        assertEquals(connectionResult.transition.snapshot, registry.currentSessionOrNull()?.snapshot)
    }

    @Test
    fun `edge connection failure after accepted start records failed tunnel without installing session`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val coordinator =
            CloudflareTunnelStartAndConnectCoordinator(
                controlPlane = controlPlane,
                connector =
                    CloudflareTunnelEdgeConnector {
                        CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable)
                    },
                sessionRegistry = registry,
            )

        val result =
            assertIs<CloudflareTunnelStartAndConnectCoordinatorResult.ConnectionAttempted>(
                coordinator.startAndConnectIfEnabled(enabled = true, rawTunnelToken = validToken()),
            )

        val connectionResult = assertIs<CloudflareTunnelConnectionCoordinatorResult.Applied>(result.connectionResult)
        assertEquals(
            CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable),
            connectionResult.connectionResult,
        )
        assertEquals(CloudflareTunnelStatus.failed("EdgeUnavailable"), controlPlane.currentStatus)
        assertEquals(null, registry.currentSessionOrNull())
    }

    @Test
    fun `disabled invalid and duplicate starts do not attempt edge connection`() {
        val cases =
            listOf(
                StartCase(
                    controlPlane = CloudflareTunnelControlPlane(),
                    enabled = false,
                    rawTunnelToken = "not a token",
                    expectedStatus = CloudflareTunnelStatus.disabled(),
                ),
                StartCase(
                    controlPlane = CloudflareTunnelControlPlane(),
                    enabled = true,
                    rawTunnelToken = null,
                    expectedStatus = CloudflareTunnelStatus.disabled(),
                ),
                StartCase(
                    controlPlane = CloudflareTunnelControlPlane(),
                    enabled = true,
                    rawTunnelToken = "not base64",
                    expectedStatus = CloudflareTunnelStatus.disabled(),
                ),
                StartCase(
                    controlPlane = activeControlPlane(CloudflareTunnelStatus.starting()),
                    enabled = true,
                    rawTunnelToken = validToken(),
                    expectedStatus = CloudflareTunnelStatus.starting(),
                ),
                StartCase(
                    controlPlane = activeControlPlane(CloudflareTunnelStatus.connected()),
                    enabled = true,
                    rawTunnelToken = validToken(),
                    expectedStatus = CloudflareTunnelStatus.connected(),
                ),
                StartCase(
                    controlPlane = activeControlPlane(CloudflareTunnelStatus.degraded()),
                    enabled = true,
                    rawTunnelToken = validToken(),
                    expectedStatus = CloudflareTunnelStatus.degraded(),
                ),
            )

        cases.forEach { case ->
            var connectorCalled = false
            val coordinator =
                CloudflareTunnelStartAndConnectCoordinator(
                    controlPlane = case.controlPlane,
                    connector =
                        CloudflareTunnelEdgeConnector {
                            connectorCalled = true
                            CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection())
                        },
                )

            val result =
                assertIs<CloudflareTunnelStartAndConnectCoordinatorResult.NoConnectionAttempt>(
                    coordinator.startAndConnectIfEnabled(
                        enabled = case.enabled,
                        rawTunnelToken = case.rawTunnelToken,
                    ),
                )

            assertFalse(connectorCalled)
            assertEquals(case.expectedStatus, case.controlPlane.currentStatus)
            assertEquals(case.expectedStatus, result.startResult.statusOrNull() ?: case.controlPlane.currentStatus)
        }
    }

    @Test
    fun `diagnostics do not expose token-derived values`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val result =
            CloudflareTunnelStartAndConnectCoordinatorResult.ConnectionAttempted(
                startTransition = controlPlane.apply(CloudflareTunnelEvent.StartRequested),
                connectionResult =
                    CloudflareTunnelConnectionCoordinatorResult.Applied(
                        connectionResult = CloudflareTunnelEdgeConnectionResult.Connected(TrackableEdgeConnection()),
                        transition = controlPlane.apply(CloudflareTunnelEvent.Connected),
                    ),
            )

        val rendered = "$result"

        assertFalse(rendered.contains("account-secret"))
        assertFalse(rendered.contains(ByteArray(32) { index -> (index + 1).toByte() }.base64()))
        assertFalse(rendered.contains("edge.secret"))
        assertTrue(rendered.contains("ConnectionAttempted"))
    }

    @Test
    fun `no connection attempt result rejects accepted starting transition`() {
        val transition =
            CloudflareTunnelControlPlane()
                .apply(CloudflareTunnelEvent.StartRequested)

        val failure =
            kotlin.runCatching {
                CloudflareTunnelStartAndConnectCoordinatorResult.NoConnectionAttempt(
                    CloudflareTunnelStartCoordinatorResult.Ready(
                        credentials = credentials(),
                        transition = transition,
                    ),
                )
            }

        assertTrue(failure.isFailure)
    }

    private data class StartCase(
        val controlPlane: CloudflareTunnelControlPlane,
        val enabled: Boolean,
        val rawTunnelToken: String?,
        val expectedStatus: CloudflareTunnelStatus,
    )

    private fun validToken(): String = encodedToken(
        """{"a":"account-tag","s":"${ByteArray(
            32,
        ) { index -> (index + 1).toByte() }.base64()}","t":"123e4567-e89b-12d3-a456-426614174000","e":"edge.example.com"}""",
    )

    private fun encodedToken(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun activeControlPlane(status: CloudflareTunnelStatus): CloudflareTunnelControlPlane = CloudflareTunnelControlPlane().also { controlPlane ->
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        if (status == CloudflareTunnelStatus.connected() || status == CloudflareTunnelStatus.degraded()) {
            controlPlane.apply(CloudflareTunnelEvent.Connected)
        }
        if (status == CloudflareTunnelStatus.degraded()) {
            controlPlane.apply(CloudflareTunnelEvent.Degraded)
        }
    }

    private fun credentials(): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        tunnelSecret = ByteArray(32) { index -> (index + 1).toByte() },
        endpoint = null,
    )

    private fun CloudflareTunnelStartCoordinatorResult.statusOrNull(): CloudflareTunnelStatus? = when (this) {
        is CloudflareTunnelStartCoordinatorResult.Ready -> transition.status
        is CloudflareTunnelStartCoordinatorResult.Disabled -> snapshot.status
        is CloudflareTunnelStartCoordinatorResult.FailedStartup -> null
    }

    private class TrackableEdgeConnection : CloudflareTunnelEdgeConnection {
        override fun close() = Unit
    }
}
