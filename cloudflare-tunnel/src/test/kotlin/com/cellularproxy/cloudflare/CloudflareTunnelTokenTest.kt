package com.cellularproxy.cloudflare

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudflareTunnelTokenTest {
    @Test
    fun `parses remotely-managed tunnel token credentials`() {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = ByteArray(32) { index -> (index + 1).toByte() }
        val token = encodedToken(
            """{"a":"account-tag","s":"${secret.base64()}","t":"$tunnelId","e":"edge.example.com"}""",
        )

        val result = CloudflareTunnelToken.parse(token)

        val parsed = assertIs<CloudflareTunnelTokenParseResult.Valid>(result).token
        assertEquals("account-tag", parsed.credentials.accountTag)
        assertEquals(tunnelId, parsed.credentials.tunnelId)
        assertContentEquals(secret, parsed.credentials.tunnelSecret)
        assertEquals("edge.example.com", parsed.credentials.endpoint)
    }

    @Test
    fun `parses url-safe unpadded tunnel token and secret encodings`() {
        val tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secret = ByteArray(32) { 0xFF.toByte() }
        val token = urlSafeEncodedToken(
            """{"a":"account-tag","s":"${secret.base64UrlNoPadding()}","t":"$tunnelId","e":":bk%*Wa3&4w}AxL5kC7?"}""",
        )

        val result = CloudflareTunnelToken.parse(token)

        val parsed = assertIs<CloudflareTunnelTokenParseResult.Valid>(result).token
        assertEquals("account-tag", parsed.credentials.accountTag)
        assertEquals(tunnelId, parsed.credentials.tunnelId)
        assertContentEquals(secret, parsed.credentials.tunnelSecret)
        assertEquals(":bk%*Wa3&4w}AxL5kC7?", parsed.credentials.endpoint)
    }

    @Test
    fun `tunnel secret bytes are defensively copied`() {
        val tokenSecret = ByteArray(32) { index -> (index + 1).toByte() }
        val credentials = CloudflareTunnelCredentials(
            accountTag = "account-tag",
            tunnelId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            tunnelSecret = tokenSecret,
            endpoint = null,
        )
        tokenSecret[0] = 99

        assertContentEquals(ByteArray(32) { index -> (index + 1).toByte() }, credentials.tunnelSecret)

        val parsedToken = assertIs<CloudflareTunnelTokenParseResult.Valid>(
            CloudflareTunnelToken.parse(
                encodedToken(
                    """{"a":"account-tag","s":"${ByteArray(32) { index -> (index + 1).toByte() }.base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""",
                ),
            ),
        ).token

        val firstRead = parsedToken.credentials.tunnelSecret
        firstRead[0] = 99

        assertContentEquals(ByteArray(32) { index -> (index + 1).toByte() }, parsedToken.credentials.tunnelSecret)
    }

    @Test
    fun `rejects blank malformed non-json and non-object tokens`() {
        val cases = mapOf(
            "" to CloudflareTunnelTokenInvalidReason.Blank,
            "not base64" to CloudflareTunnelTokenInvalidReason.NotBase64,
            encodedToken("not json") to CloudflareTunnelTokenInvalidReason.NotJsonObject,
            encodedToken("[]") to CloudflareTunnelTokenInvalidReason.NotJsonObject,
        )

        cases.forEach { (token, expectedReason) ->
            val invalid = assertIs<CloudflareTunnelTokenParseResult.Invalid>(
                CloudflareTunnelToken.parse(token),
                "Expected invalid result for $token",
            )

            assertEquals(expectedReason, invalid.reason)
        }
    }

    @Test
    fun `rejects outer token json that is not strict utf8`() {
        val bytes = byteArrayOf(
            *"""{"a":"""".toByteArray(Charsets.UTF_8),
            0xC3.toByte(),
            0x28.toByte(),
            *"""","s":"${byteArrayOf(1).base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""".toByteArray(
                Charsets.UTF_8,
            ),
        )

        val invalid = assertIs<CloudflareTunnelTokenParseResult.Invalid>(
            CloudflareTunnelToken.parse(Base64.getEncoder().encodeToString(bytes)),
        )

        assertEquals(CloudflareTunnelTokenInvalidReason.NotJsonObject, invalid.reason)
    }

    @Test
    fun `rejects missing or invalid required credential fields`() {
        val cases = mapOf(
            """{"s":"${byteArrayOf(1).base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.MissingAccountTag,
            """{"a":"","s":"${byteArrayOf(1).base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.MissingAccountTag,
            """{"a":"account-tag","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.MissingTunnelSecret,
            """{"a":"account-tag","s":"","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.MissingTunnelSecret,
            """{"a":"account-tag","s":"not base64","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.InvalidTunnelSecret,
            """{"a":"account-tag","s":"${ByteArray(31) { 1 }.base64()}","t":"123e4567-e89b-12d3-a456-426614174000"}""" to
                CloudflareTunnelTokenInvalidReason.InvalidTunnelSecret,
            """{"a":"account-tag","s":"${ByteArray(32) { 1 }.base64()}"}""" to
                CloudflareTunnelTokenInvalidReason.MissingTunnelId,
            """{"a":"account-tag","s":"${ByteArray(32) { 1 }.base64()}","t":"not-a-uuid"}""" to
                CloudflareTunnelTokenInvalidReason.InvalidTunnelId,
        )

        cases.forEach { (json, expectedReason) ->
            val invalid = assertIs<CloudflareTunnelTokenParseResult.Invalid>(
                CloudflareTunnelToken.parse(encodedToken(json)),
                "Expected invalid result for $json",
            )

            assertEquals(expectedReason, invalid.reason)
        }
    }

    @Test
    fun `diagnostics do not expose raw token or credentials`() {
        val secret = ByteArray(32) { index -> (index + 4).toByte() }.base64()
        val tokenValue = encodedToken(
            """{"a":"account-secret","s":"$secret","t":"123e4567-e89b-12d3-a456-426614174000","e":"edge.secret"}""",
        )
        val token = assertIs<CloudflareTunnelTokenParseResult.Valid>(
            CloudflareTunnelToken.parse(tokenValue),
        ).token

        val rendered = "${token.credentials} $token ${CloudflareTunnelTokenParseResult.Valid(token)}"

        assertFalse(rendered.contains(tokenValue), "Diagnostics must not include the raw token")
        assertFalse(rendered.contains("account-secret"), "Diagnostics must not include account tags")
        assertFalse(rendered.contains(secret), "Diagnostics must not include tunnel secrets")
        assertFalse(rendered.contains("edge.secret"), "Diagnostics must not include endpoint labels")
        assertTrue(rendered.contains("CloudflareTunnelToken"), "Diagnostics should still identify token objects")
    }

    private fun encodedToken(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun urlSafeEncodedToken(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun ByteArray.base64(): String =
        Base64.getEncoder().encodeToString(this)

    private fun ByteArray.base64UrlNoPadding(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
