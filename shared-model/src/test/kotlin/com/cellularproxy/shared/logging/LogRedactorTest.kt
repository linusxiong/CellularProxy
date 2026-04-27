package com.cellularproxy.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRedactorTest {
    @Test
    fun `redacts sensitive headers without changing non-sensitive headers`() {
        val message =
            """
            Authorization: Bearer management-token
            Proxy-Authorization: Basic proxy-secret
            Cookie: session=clear
            Set-Cookie: session=clear
            Host: example.test
            """.trimIndent()

        val redacted = LogRedactor.redact(message)

        assertEquals(
            """
            Authorization: [REDACTED]
            Proxy-Authorization: [REDACTED]
            Cookie: [REDACTED]
            Set-Cookie: [REDACTED]
            Host: example.test
            """.trimIndent(),
            redacted,
        )
    }

    @Test
    fun `redacts url query strings from log messages`() {
        val message = "CONNECT example.test:443 via https://management.example.test/api/status?token=abc&debug=true"

        val redacted = LogRedactor.redact(message)

        assertEquals(
            "CONNECT example.test:443 via https://management.example.test/api/status?[REDACTED]",
            redacted,
        )
    }

    @Test
    fun `redacts relative path query strings from log messages`() {
        val message = "GET /api/status?token=abc&debug=true HTTP/1.1"

        val redacted = LogRedactor.redact(message)

        assertEquals("GET /api/status?[REDACTED] HTTP/1.1", redacted)
    }

    @Test
    fun `redacts root path query strings from log messages`() {
        val message = "GET /?token=abc&debug=true HTTP/1.1"

        val redacted = LogRedactor.redact(message)

        assertEquals("GET /?[REDACTED] HTTP/1.1", redacted)
    }

    @Test
    fun `redacts sensitive headers in structured log messages`() {
        val message = """headers={Authorization=Bearer abc, "Cookie":"session=clear", Host=example.test}"""

        val redacted = LogRedactor.redact(message)

        assertEquals("""headers={Authorization=[REDACTED], "Cookie":"[REDACTED]", Host=example.test}""", redacted)
    }

    @Test
    fun `preserves structured delimiters when redacting quoted url query strings`() {
        val message = """url="https://example.test/api?token=abc", path="/?debug=true""""

        val redacted = LogRedactor.redact(message)

        assertEquals("""url="https://example.test/api?[REDACTED]", path="/?[REDACTED]"""", redacted)
    }

    @Test
    fun `redacts configured management proxy and cloudflare secrets`() {
        val secrets =
            LogRedactionSecrets(
                managementApiToken = "management-token",
                proxyCredential = "proxy-user:proxy-password",
                cloudflareTunnelToken = "cloudflare-token",
            )
        val message =
            """
            management=management-token
            proxy=proxy-user:proxy-password
            tunnel=cloudflare-token
            ordinary=visible
            """.trimIndent()

        val redacted = LogRedactor.redact(message, secrets)

        assertFalse(redacted.contains("management-token"))
        assertFalse(redacted.contains("proxy-user:proxy-password"))
        assertFalse(redacted.contains("cloudflare-token"))
        assertEquals(
            """
            management=[REDACTED]
            proxy=[REDACTED]
            tunnel=[REDACTED]
            ordinary=visible
            """.trimIndent(),
            redacted,
        )
    }

    @Test
    fun `ignores blank configured secrets`() {
        val secrets =
            LogRedactionSecrets(
                managementApiToken = "",
                proxyCredential = " ",
                cloudflareTunnelToken = null,
            )

        assertEquals("ordinary log line", LogRedactor.redact("ordinary log line", secrets))
    }

    @Test
    fun `redacts overlapping configured secrets using the longest value first`() {
        val secrets =
            LogRedactionSecrets(
                managementApiToken = "abc123",
                proxyCredential = "abc",
            )

        val redacted = LogRedactor.redact("token=abc123 prefix=abc", secrets)

        assertEquals("token=[REDACTED] prefix=[REDACTED]", redacted)
    }

    @Test
    fun `redacts configured secrets inside url queries without leaking redaction delimiters`() {
        val secrets = LogRedactionSecrets(managementApiToken = "raw-token")

        val redacted =
            LogRedactor.redact(
                "url=https://edge.example.test/cdn-cgi?token=raw-token",
                secrets,
            )

        assertEquals("url=https://edge.example.test/cdn-cgi?[REDACTED]", redacted)
    }
}
