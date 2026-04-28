package com.cellularproxy.cloudflare

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class CloudflareTunnelEndpointFailoverConnectorTest {
    @Test
    fun `tries resolved endpoints until one connects`() {
        val attemptedEndpoints = mutableListOf<CloudflareTunnelEdgeEndpoint>()
        val connectedConnection = TrackableEdgeConnection()
        val connector =
            CloudflareTunnelEndpointFailoverConnector(
                dialer =
                    CloudflareTunnelEndpointDialer { endpoint, _ ->
                        attemptedEndpoints += endpoint
                        if (attemptedEndpoints.size == 1) {
                            CloudflareTunnelEdgeConnectionResult.Failed(
                                CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable,
                            )
                        } else {
                            CloudflareTunnelEdgeConnectionResult.Connected(connectedConnection)
                        }
                    },
            )

        val result = assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(connector.connect(credentials()))

        assertSame(connectedConnection, result.connection)
        assertEquals(
            listOf(
                CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", 7844),
                CloudflareTunnelEdgeEndpoint("region2.v2.argotunnel.com", 7844),
            ),
            attemptedEndpoints,
        )
    }

    @Test
    fun `continues to next endpoint when dialer throws ordinary exception`() {
        val attemptedEndpoints = mutableListOf<CloudflareTunnelEdgeEndpoint>()
        val connectedConnection = TrackableEdgeConnection()
        val connector =
            CloudflareTunnelEndpointFailoverConnector(
                dialer =
                    CloudflareTunnelEndpointDialer { endpoint, _ ->
                        attemptedEndpoints += endpoint
                        if (attemptedEndpoints.size == 1) {
                            throw java.io.IOException("region unavailable")
                        }
                        CloudflareTunnelEdgeConnectionResult.Connected(connectedConnection)
                    },
            )

        val result = assertIs<CloudflareTunnelEdgeConnectionResult.Connected>(connector.connect(credentials()))

        assertSame(connectedConnection, result.connection)
        assertEquals(
            listOf(
                CloudflareTunnelEdgeEndpoint("region1.v2.argotunnel.com", 7844),
                CloudflareTunnelEdgeEndpoint("region2.v2.argotunnel.com", 7844),
            ),
            attemptedEndpoints,
        )
    }

    private fun credentials(): CloudflareTunnelCredentials = CloudflareTunnelCredentials(
        accountTag = "account-tag",
        tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        tunnelSecret = ByteArray(32) { index -> index.toByte() },
        endpoint = null,
    )

    private class TrackableEdgeConnection : CloudflareTunnelEdgeConnection {
        override fun close() = Unit
    }
}
