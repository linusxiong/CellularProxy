package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagementApiCloudflareActionResponsesTest {
    @Test
    fun `accepted cloudflare transition renders accepted response`() {
        val response =
            ManagementApiCloudflareActionResponses.transition(
                result =
                    CloudflareTunnelTransitionResult(
                        disposition = CloudflareTunnelTransitionDisposition.Accepted,
                        status = CloudflareTunnelStatus.starting(),
                    ),
                secrets = LogRedactionSecrets(),
            )

        assertEquals(202, response.statusCode)
        assertEquals(
            """{"accepted":true,"disposition":"accepted","cloudflare":{"state":"starting","remoteManagementAvailable":false,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `duplicate cloudflare transition renders conflict response`() {
        val response =
            ManagementApiCloudflareActionResponses.transition(
                result =
                    CloudflareTunnelTransitionResult(
                        disposition = CloudflareTunnelTransitionDisposition.Duplicate,
                        status = CloudflareTunnelStatus.connected(),
                    ),
                secrets = LogRedactionSecrets(),
            )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"duplicate","cloudflare":{"state":"connected","remoteManagementAvailable":true,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `ignored cloudflare transition renders conflict response`() {
        val response =
            ManagementApiCloudflareActionResponses.transition(
                result =
                    CloudflareTunnelTransitionResult(
                        disposition = CloudflareTunnelTransitionDisposition.Ignored,
                        status = CloudflareTunnelStatus.disabled(),
                    ),
                secrets = LogRedactionSecrets(),
            )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"ignored","cloudflare":{"state":"disabled","remoteManagementAvailable":false,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `cloudflare transition response redacts configured secrets from failure reasons`() {
        val response =
            ManagementApiCloudflareActionResponses.transition(
                result =
                    CloudflareTunnelTransitionResult(
                        disposition = CloudflareTunnelTransitionDisposition.Duplicate,
                        status =
                            CloudflareTunnelStatus(
                                state = CloudflareTunnelState.Failed,
                                failureReason = "edge rejected cloudflare-secret at https://edge.example.test/tunnel?token=cloudflare-secret",
                            ),
                    ),
                secrets = LogRedactionSecrets(cloudflareTunnelToken = "cloudflare-secret"),
            )

        assertEquals(
            """{"accepted":false,"disposition":"duplicate","cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"edge rejected [REDACTED] at https://edge.example.test/tunnel?[REDACTED]"}}""",
            response.body,
        )
        assertFalse(response.body.contains("cloudflare-secret"))
    }
}
