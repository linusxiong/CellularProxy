package com.cellularproxy.shared.proxy

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyAuthenticationPolicyTest {
    private val credential = ProxyCredential(username = "proxy-user", password = "proxy-pass")

    @Test
    fun `disabled proxy authentication accepts missing authorization`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = false, credential = credential),
                proxyAuthorization = null,
            )

        assertTrue(decision.accepted)
        assertEquals(null, decision.rejectionReason)
    }

    @Test
    fun `enabled proxy authentication accepts matching basic credentials`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                proxyAuthorization = basicHeader("proxy-user", "proxy-pass"),
            )

        assertTrue(decision.accepted)
        assertEquals(null, decision.rejectionReason)
    }

    @Test
    fun `enabled proxy authentication rejects missing authorization`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                proxyAuthorization = null,
            )

        assertFalse(decision.accepted)
        assertEquals(ProxyAuthenticationRejectionReason.MissingAuthorization, decision.rejectionReason)
    }

    @Test
    fun `enabled proxy authentication rejects unsupported schemes`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                proxyAuthorization = "Bearer token",
            )

        assertFalse(decision.accepted)
        assertEquals(ProxyAuthenticationRejectionReason.UnsupportedScheme, decision.rejectionReason)
    }

    @Test
    fun `enabled proxy authentication rejects malformed basic credentials`() {
        val decisions =
            listOf(
                ProxyAuthenticationPolicy.evaluate(
                    config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                    proxyAuthorization = "Basic !!!not-base64!!!",
                ),
                ProxyAuthenticationPolicy.evaluate(
                    config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                    proxyAuthorization = Base64.getEncoder().encodeToString("missing-separator".toByteArray()).let { "Basic $it" },
                ),
                ProxyAuthenticationPolicy.evaluate(
                    config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                    proxyAuthorization = "Basic ${Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte()))}",
                ),
            )

        decisions.forEach { decision ->
            assertFalse(decision.accepted)
            assertEquals(ProxyAuthenticationRejectionReason.MalformedCredentials, decision.rejectionReason)
        }
    }

    @Test
    fun `enabled proxy authentication rejects mismatched credentials`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                proxyAuthorization = basicHeader("proxy-user", "wrong-pass"),
            )

        assertFalse(decision.accepted)
        assertEquals(ProxyAuthenticationRejectionReason.CredentialMismatch, decision.rejectionReason)
    }

    @Test
    fun `authorization parsing tolerates scheme case and surrounding whitespace`() {
        val decision =
            ProxyAuthenticationPolicy.evaluate(
                config = ProxyAuthenticationConfig(authEnabled = true, credential = credential),
                proxyAuthorization = "  bAsIc ${encoded("proxy-user:proxy-pass")}  ",
            )

        assertTrue(decision.accepted)
    }

    @Test
    fun `proxy credential requires nonblank username and password`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyCredential(username = "", password = "proxy-pass")
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyCredential(username = "proxy-user", password = " ")
        }
    }

    @Test
    fun `proxy credential rejects usernames containing separators`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyCredential(username = "proxy:user", password = "proxy-pass")
        }
    }

    @Test
    fun `proxy authentication config string representation redacts the full credential`() {
        val config = ProxyAuthenticationConfig(authEnabled = true, credential = credential)

        val renderedConfig = config.toString()
        val renderedCredential = credential.toString()

        assertFalse("proxy-user" in renderedConfig)
        assertFalse("proxy-pass" in renderedConfig)
        assertFalse("proxy-user" in renderedCredential)
        assertFalse("proxy-pass" in renderedCredential)
    }

    private fun basicHeader(
        username: String,
        password: String,
    ): String = "Basic ${encoded("$username:$password")}"

    private fun encoded(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
