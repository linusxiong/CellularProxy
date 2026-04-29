package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudflareTunnelStartCoordinatorTest {
    @Test
    fun `valid enabled startup token starts disabled tunnel and returns parsed credentials`() {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = ByteArray(32) { index -> (index + 1).toByte() }
        val controlPlane = CloudflareTunnelControlPlane()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.Ready>(
                coordinator.startIfEnabled(
                    enabled = true,
                    rawTunnelToken =
                        encodedToken(
                            """{"a":"account-tag","s":"${secret.base64()}","t":"$tunnelId","e":"edge.example.com"}""",
                        ),
                ),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), result.transition.status)
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
        assertEquals("account-tag", result.credentials.accountTag)
        assertEquals(tunnelId, result.credentials.tunnelId)
        assertContentEquals(secret, result.credentials.tunnelSecret)
        assertEquals("edge.example.com", result.credentials.endpoint)
    }

    @Test
    fun `valid enabled startup token returns duplicate transition for already active tunnel`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val initialStart = controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.Ready>(
                coordinator.startIfEnabled(
                    enabled = true,
                    rawTunnelToken = validToken(),
                ),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.starting(), result.transition.status)
        assertEquals(initialStart.snapshot, result.transition.snapshot)
        assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
    }

    @Test
    fun `valid enabled startup token returns duplicate transition for connected tunnel`() {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        val connected = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.Ready>(
                coordinator.startIfEnabled(
                    enabled = true,
                    rawTunnelToken = validToken(),
                ),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.connected(), result.transition.status)
        assertEquals(connected, result.transition.snapshot)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
    }

    @Test
    fun `valid enabled startup token returns duplicate transition for degraded tunnel`() {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        controlPlane.apply(CloudflareTunnelEvent.Degraded)
        val degraded = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.Ready>(
                coordinator.startIfEnabled(
                    enabled = true,
                    rawTunnelToken = validToken(),
                ),
            )

        assertEquals(CloudflareTunnelTransitionDisposition.Duplicate, result.transition.disposition)
        assertEquals(CloudflareTunnelStatus.degraded(), result.transition.status)
        assertEquals(degraded, result.transition.snapshot)
        assertEquals(CloudflareTunnelStatus.degraded(), controlPlane.currentStatus)
    }

    @Test
    fun `valid enabled startup token restarts stopped and failed tunnels`() {
        listOf(
            CloudflareTunnelStatus.stopped(),
            CloudflareTunnelStatus.failed("ProtocolError"),
        ).forEach { initialStatus ->
            val controlPlane = CloudflareTunnelControlPlane(initialStatus)
            val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

            val result =
                assertIs<CloudflareTunnelStartCoordinatorResult.Ready>(
                    coordinator.startIfEnabled(
                        enabled = true,
                        rawTunnelToken = validToken(),
                    ),
                )

            assertEquals(CloudflareTunnelTransitionDisposition.Accepted, result.transition.disposition)
            assertEquals(CloudflareTunnelStatus.starting(), result.transition.status)
            assertEquals(CloudflareTunnelStatus.starting(), controlPlane.currentStatus)
        }
    }

    @Test
    fun `missing startup token is reported without mutating lifecycle state`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.FailedStartup>(
                coordinator.startIfEnabled(enabled = true, rawTunnelToken = "   "),
            )

        assertEquals(CloudflareTunnelStartupFailure.MissingTunnelToken, result.failure)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `invalid startup token is reported without mutating lifecycle state`() {
        val controlPlane = CloudflareTunnelControlPlane()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.FailedStartup>(
                coordinator.startIfEnabled(enabled = true, rawTunnelToken = "not base64"),
            )

        assertEquals(CloudflareTunnelStartupFailure.InvalidTunnelToken, result.failure)
        assertEquals(CloudflareTunnelStatus.disabled(), controlPlane.currentStatus)
    }

    @Test
    fun `disabled startup ignores token text and leaves control plane unchanged`() {
        val controlPlane = CloudflareTunnelControlPlane()
        controlPlane.apply(CloudflareTunnelEvent.StartRequested)
        controlPlane.apply(CloudflareTunnelEvent.Connected)
        val snapshotBefore = controlPlane.snapshot()
        val coordinator = CloudflareTunnelStartCoordinator(controlPlane)

        val result =
            assertIs<CloudflareTunnelStartCoordinatorResult.Disabled>(
                coordinator.startIfEnabled(enabled = false, rawTunnelToken = "not a valid token"),
            )

        assertEquals(snapshotBefore, result.snapshot)
        assertEquals(CloudflareTunnelStatus.connected(), controlPlane.currentStatus)
    }

    @Test
    fun `start coordinator diagnostics do not expose token-derived values`() {
        val result =
            CloudflareTunnelStartCoordinatorResult.Ready(
                credentials =
                    CloudflareTunnelCredentials(
                        accountTag = "account-secret",
                        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                        tunnelSecret = ByteArray(32) { index -> (index + 1).toByte() },
                        endpoint = "edge.secret",
                    ),
                transition = CloudflareTunnelControlPlane().apply(CloudflareTunnelEvent.StartRequested),
            )

        val rendered = result.toString()

        assertEquals("CloudflareTunnelStartCoordinatorResult.Ready(credentials=<redacted>, transition=Accepted)", rendered)
        assertFalse(rendered.contains("account-secret"))
        assertFalse(rendered.contains(ByteArray(32) { index -> (index + 1).toByte() }.base64()))
        assertFalse(rendered.contains("edge.secret"))
    }

    @Test
    fun `ready result rejects non-start transition`() {
        val transition =
            CloudflareTunnelControlPlane()
                .apply(CloudflareTunnelEvent.Connected)

        val failure =
            kotlin.runCatching {
                CloudflareTunnelStartCoordinatorResult.Ready(
                    credentials = credentials(),
                    transition = transition,
                )
            }

        assertTrue(failure.isFailure)
    }

    private fun validToken(): String = encodedToken(
        """{"a":"account-tag","s":"${ByteArray(
            32,
        ) { index -> (index + 1).toByte() }.base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""",
    )

    private fun credentials(): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        tunnelSecret = ByteArray(32) { index -> (index + 1).toByte() },
        endpoint = null,
    )

    private fun encodedToken(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
}
