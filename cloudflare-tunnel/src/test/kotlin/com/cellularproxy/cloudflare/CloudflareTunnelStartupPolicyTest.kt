package com.cellularproxy.cloudflare

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CloudflareTunnelStartupPolicyTest {
    @Test
    fun `disabled tunnel startup does not require or parse a token`() {
        val decision =
            CloudflareTunnelStartupPolicy.evaluate(
                enabled = false,
                rawTunnelToken = "not a valid token",
            )

        assertEquals(CloudflareTunnelStartupDecision.Disabled, decision)
    }

    @Test
    fun `enabled tunnel startup fails when token is missing`() {
        val nullToken =
            assertIs<CloudflareTunnelStartupDecision.Failed>(
                CloudflareTunnelStartupPolicy.evaluate(enabled = true, rawTunnelToken = null),
            )
        val blankToken =
            assertIs<CloudflareTunnelStartupDecision.Failed>(
                CloudflareTunnelStartupPolicy.evaluate(enabled = true, rawTunnelToken = "   "),
            )

        assertEquals(CloudflareTunnelStartupFailure.MissingTunnelToken, nullToken.failure)
        assertEquals(CloudflareTunnelStartupFailure.MissingTunnelToken, blankToken.failure)
    }

    @Test
    fun `enabled tunnel startup fails when token is invalid`() {
        val decision =
            assertIs<CloudflareTunnelStartupDecision.Failed>(
                CloudflareTunnelStartupPolicy.evaluate(
                    enabled = true,
                    rawTunnelToken = encodedToken("""{"a":"account-tag","s":"not base64","t":"123e4567-e89b-12d3-a456-426614174000"}"""),
                ),
            )

        assertEquals(CloudflareTunnelStartupFailure.InvalidTunnelToken, decision.failure)
    }

    @Test
    fun `enabled tunnel startup returns parsed credentials for valid token`() {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = ByteArray(32) { index -> (index + 7).toByte() }

        val decision =
            assertIs<CloudflareTunnelStartupDecision.Ready>(
                CloudflareTunnelStartupPolicy.evaluate(
                    enabled = true,
                    rawTunnelToken =
                        encodedToken(
                            """{"a":"account-tag","s":"${secret.base64()}","t":"$tunnelId","e":"edge.example.com"}""",
                        ),
                ),
            )

        assertEquals("account-tag", decision.credentials.accountTag)
        assertEquals(tunnelId, decision.credentials.tunnelId)
        assertContentEquals(secret, decision.credentials.tunnelSecret)
        assertEquals("edge.example.com", decision.credentials.endpoint)
    }

    @Test
    fun `startup decision diagnostics do not expose token-derived values`() {
        val secret = ByteArray(32) { index -> (index + 1).toByte() }.base64()
        val decision =
            CloudflareTunnelStartupDecision.Ready(
                CloudflareTunnelCredentials(
                    accountTag = "account-secret",
                    tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    tunnelSecret = ByteArray(32) { index -> (index + 1).toByte() },
                    endpoint = "edge.secret",
                ),
            )

        val rendered = decision.toString()

        assertEquals("CloudflareTunnelStartupDecision.Ready(credentials=<redacted>)", rendered)
        kotlin.test.assertFalse(rendered.contains("account-secret"))
        kotlin.test.assertFalse(rendered.contains(secret))
        kotlin.test.assertFalse(rendered.contains("edge.secret"))
    }

    private fun encodedToken(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
}
