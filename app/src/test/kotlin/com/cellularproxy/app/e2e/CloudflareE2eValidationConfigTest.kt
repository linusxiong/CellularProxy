package com.cellularproxy.app.e2e

import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudflareE2eValidationConfigTest {
    @Test
    fun `disabled when token input is absent or blank`() {
        listOf(
            emptyMap(),
            mapOf(CloudflareE2eValidationConfigKeys.tunnelToken to "   "),
        ).forEach { values ->
            val config = CloudflareE2eValidationConfig.fromLocalValues(values)

            assertEquals(CloudflareE2eValidationConfig.Disabled, config)
        }
    }

    @Test
    fun `ready config trims local e2e values and exposes only safe summary`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to "  $validTunnelToken  ",
                    CloudflareE2eValidationConfigKeys.managementApiToken to "  management-secret  ",
                    CloudflareE2eValidationConfigKeys.managementHostname to "  management.example.test  ",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("management.example.test", ready.managementHostname)
        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=present, hostname=management.example.test",
            ready.safeSummary,
        )
        assertFalse(ready.safeSummary.contains(validTunnelToken))
        assertFalse(ready.safeSummary.contains("management-secret"))
        assertFalse(ready.toString().contains(validTunnelToken))
        assertFalse(ready.toString().contains("management-secret"))
    }

    @Test
    fun `ready config summary and string strip hostname path query and fragment details`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https://management.example.test/private/path?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=https://management.example.test",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=https://management.example.test"))
        assertFalse(ready.safeSummary.contains("query-secret"))
        assertFalse(ready.safeSummary.contains("fragment-secret"))
        assertFalse(ready.safeSummary.contains("/private/path"))
        assertFalse(ready.toString().contains("query-secret"))
        assertFalse(ready.toString().contains("fragment-secret"))
        assertFalse(ready.toString().contains("/private/path"))
    }

    @Test
    fun `ready config summary and string strip hostname userinfo details`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https://operator:hostname-secret@management.example.test:8443/private/path",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals("https://management.example.test:8443", ready.managementHostname)
        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=https://management.example.test:8443",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=https://management.example.test:8443"))
        assertFalse(ready.safeSummary.contains("operator"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.safeSummary.contains("/private/path"))
        assertFalse(ready.toString().contains("operator"))
        assertFalse(ready.toString().contains("hostname-secret"))
        assertFalse(ready.toString().contains("/private/path"))
    }

    @Test
    fun `ready config summary strips userinfo for url-like hostname when uri host is unavailable`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https://operator:hostname-secret@management_example.test:8443/private/path?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=https://management_example.test:8443",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=https://management_example.test:8443"))
        assertFalse(ready.safeSummary.contains("operator"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.safeSummary.contains("query-secret"))
        assertFalse(ready.safeSummary.contains("fragment-secret"))
        assertFalse(ready.safeSummary.contains("/private/path"))
        assertFalse(ready.toString().contains("operator"))
        assertFalse(ready.toString().contains("hostname-secret"))
        assertFalse(ready.toString().contains("query-secret"))
        assertFalse(ready.toString().contains("fragment-secret"))
        assertFalse(ready.toString().contains("/private/path"))
    }

    @Test
    fun `ready config treats url-like hostname with empty authority as not configured`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https:///private/token-secret?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(null, ready.managementHostname)
        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=not configured",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=not configured"))
        assertFalse(ready.safeSummary.contains("token-secret"))
        assertFalse(ready.safeSummary.contains("query-secret"))
        assertFalse(ready.safeSummary.contains("fragment-secret"))
        assertFalse(ready.safeSummary.contains("/private"))
        assertFalse(ready.toString().contains("token-secret"))
        assertFalse(ready.toString().contains("query-secret"))
        assertFalse(ready.toString().contains("fragment-secret"))
        assertFalse(ready.toString().contains("/private"))
    }

    @Test
    fun `ready config stores sanitized management hostname for validators`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https://operator:hostname-secret@management.example.test/private/path?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals("https://management.example.test", ready.managementHostname)
        assertFalse(ready.managementHostname.orEmpty().contains("operator"))
        assertFalse(ready.managementHostname.orEmpty().contains("hostname-secret"))
        assertFalse(ready.managementHostname.orEmpty().contains("query-secret"))
        assertFalse(ready.managementHostname.orEmpty().contains("fragment-secret"))
        assertFalse(ready.managementHostname.orEmpty().contains("/private/path"))
    }

    @Test
    fun `ready config constructor rejects unsanitized validator inputs`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = "token",
                managementApiToken = null,
                managementHostname = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = " $validTunnelToken ",
                managementApiToken = " management-secret ",
                managementHostname = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = "token",
                managementApiToken = null,
                managementHostname = "https://operator:hostname-secret@management.example.test/private?token=query-secret",
            )
        }
    }

    @Test
    fun `ready config summary strips userinfo and path for url-like hostname with missing scheme`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "://operator:hostname-secret@management.example.test/private/path?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=management.example.test",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=management.example.test"))
        assertFalse(ready.safeSummary.contains("operator"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.safeSummary.contains("query-secret"))
        assertFalse(ready.safeSummary.contains("fragment-secret"))
        assertFalse(ready.safeSummary.contains("/private"))
        assertFalse(ready.toString().contains("operator"))
        assertFalse(ready.toString().contains("hostname-secret"))
        assertFalse(ready.toString().contains("query-secret"))
        assertFalse(ready.toString().contains("fragment-secret"))
        assertFalse(ready.toString().contains("/private"))
    }

    @Test
    fun `ready config summary strips path query fragment and userinfo from scheme-less hostname`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "operator:hostname-secret@management.example.test/private/path?token=query-secret#fragment-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=management.example.test",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=management.example.test"))
        assertFalse(ready.safeSummary.contains("operator"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.safeSummary.contains("query-secret"))
        assertFalse(ready.safeSummary.contains("fragment-secret"))
        assertFalse(ready.safeSummary.contains("/private"))
        assertFalse(ready.toString().contains("operator"))
        assertFalse(ready.toString().contains("hostname-secret"))
        assertFalse(ready.toString().contains("query-secret"))
        assertFalse(ready.toString().contains("fragment-secret"))
        assertFalse(ready.toString().contains("/private"))
    }

    @Test
    fun `ready config summary strips injected header lines from scheme-less hostname`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "management.example.test\r\nAuthorization: Bearer hostname-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=management.example.test",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=management.example.test"))
        assertFalse(ready.safeSummary.contains("Authorization"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.toString().contains("Authorization"))
        assertFalse(ready.toString().contains("hostname-secret"))
    }

    @Test
    fun `ready config summary strips injected header lines from url-like hostname`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                    CloudflareE2eValidationConfigKeys.managementHostname to
                        "https://management.example.test/private\r\nAuthorization: Bearer hostname-secret",
                ),
            )

        val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

        assertEquals(
            "Cloudflare e2e validation configured: tunnelToken=present, managementApiToken=missing, hostname=https://management.example.test",
            ready.safeSummary,
        )
        assertTrue(ready.toString().contains("managementHostname=https://management.example.test"))
        assertFalse(ready.safeSummary.contains("Authorization"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.toString().contains("Authorization"))
        assertFalse(ready.toString().contains("hostname-secret"))
    }

    @Test
    fun `ready config summary strips backslash path details from hostname`() {
        listOf(
            "management.example.test\\private\\token-secret?token=query-secret#fragment-secret" to
                "hostname=management.example.test",
            "https://management.example.test\\private\\token-secret?token=query-secret#fragment-secret" to
                "hostname=https://management.example.test",
        ).forEach { (hostname, expectedSummaryHostname) ->
            val config =
                CloudflareE2eValidationConfig.fromLocalValues(
                    mapOf(
                        CloudflareE2eValidationConfigKeys.tunnelToken to validTunnelToken,
                        CloudflareE2eValidationConfigKeys.managementHostname to hostname,
                    ),
                )

            val ready = assertIs<CloudflareE2eValidationConfig.Ready>(config)

            assertTrue(ready.safeSummary.contains(expectedSummaryHostname))
            assertFalse(ready.safeSummary.contains("token-secret"))
            assertFalse(ready.safeSummary.contains("query-secret"))
            assertFalse(ready.safeSummary.contains("fragment-secret"))
            assertFalse(ready.safeSummary.contains("\\private"))
            assertFalse(ready.toString().contains("token-secret"))
            assertFalse(ready.toString().contains("query-secret"))
            assertFalse(ready.toString().contains("fragment-secret"))
            assertFalse(ready.toString().contains("\\private"))
        }
    }

    @Test
    fun `invalid token config is explicit and redacted`() {
        val config =
            CloudflareE2eValidationConfig.fromLocalValues(
                mapOf(
                    CloudflareE2eValidationConfigKeys.tunnelToken to "not-a-real-tunnel-token",
                    CloudflareE2eValidationConfigKeys.managementApiToken to "management-secret",
                ),
            )

        val invalid = assertIs<CloudflareE2eValidationConfig.InvalidTunnelToken>(config)

        assertEquals("Cloudflare e2e validation unavailable: tunnelToken=invalid", invalid.safeSummary)
        assertTrue(invalid.toString().contains("tunnelToken=[REDACTED]"))
        assertFalse(invalid.toString().contains("not-a-real-tunnel-token"))
        assertFalse(invalid.toString().contains("management-secret"))
    }

    @Test
    fun `local properties loader uses only declared e2e keys`() {
        val properties =
            Properties().apply {
                setProperty(CloudflareE2eValidationConfigKeys.tunnelToken, "  $validTunnelToken  ")
                setProperty(CloudflareE2eValidationConfigKeys.managementApiToken, "  management-secret  ")
                setProperty(CloudflareE2eValidationConfigKeys.managementHostname, "  management.example.test  ")
                setProperty("unrelated.secret", "must-not-appear")
            }

        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromProperties(properties),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("management.example.test", ready.managementHostname)
        assertFalse(ready.safeSummary.contains("must-not-appear"))
        assertFalse(ready.toString().contains("must-not-appear"))
    }

    @Test
    fun `local properties loader accepts instrumentation e2e property aliases`() {
        val properties =
            Properties().apply {
                setProperty(CloudflareE2eValidationConfigKeys.localTunnelToken, "  $validTunnelToken  ")
                setProperty(CloudflareE2eValidationConfigKeys.localManagementApiToken, "  management-secret  ")
                setProperty(CloudflareE2eValidationConfigKeys.localManagementHostname, "  management.example.test  ")
            }

        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromProperties(properties),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("management.example.test", ready.managementHostname)
    }

    @Test
    fun `local values loader accepts instrumentation e2e property aliases`() {
        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromLocalValues(
                    mapOf(
                        CloudflareE2eValidationConfigKeys.localTunnelToken to "  $validTunnelToken  ",
                        CloudflareE2eValidationConfigKeys.localManagementApiToken to "  management-secret  ",
                        CloudflareE2eValidationConfigKeys.localManagementHostname to "  management.example.test  ",
                    ),
                ),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("management.example.test", ready.managementHostname)
    }

    @Test
    fun `blank canonical local values fall back to populated instrumentation aliases`() {
        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromLocalValues(
                    mapOf(
                        CloudflareE2eValidationConfigKeys.tunnelToken to "   ",
                        CloudflareE2eValidationConfigKeys.localTunnelToken to "  $validTunnelToken  ",
                        CloudflareE2eValidationConfigKeys.managementApiToken to "   ",
                        CloudflareE2eValidationConfigKeys.localManagementApiToken to "  management-secret  ",
                        CloudflareE2eValidationConfigKeys.managementHostname to "   ",
                        CloudflareE2eValidationConfigKeys.localManagementHostname to "  management.example.test  ",
                    ),
                ),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("management.example.test", ready.managementHostname)
    }

    @Test
    fun `local e2e aliases override populated canonical values`() {
        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromLocalValues(
                    mapOf(
                        CloudflareE2eValidationConfigKeys.tunnelToken to "not-a-real-tunnel-token",
                        CloudflareE2eValidationConfigKeys.localTunnelToken to "  $validTunnelToken  ",
                        CloudflareE2eValidationConfigKeys.managementApiToken to "canonical-management-secret",
                        CloudflareE2eValidationConfigKeys.localManagementApiToken to "  local-management-secret  ",
                        CloudflareE2eValidationConfigKeys.managementHostname to "canonical.example.test",
                        CloudflareE2eValidationConfigKeys.localManagementHostname to "  local.example.test  ",
                    ),
                ),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("local-management-secret", ready.managementApiToken)
        assertEquals("local.example.test", ready.managementHostname)
    }

    @Test
    fun `instrumentation argument loader accepts forwarded Cloudflare e2e arguments`() {
        val ready =
            assertIs<CloudflareE2eValidationConfig.Ready>(
                CloudflareE2eValidationConfig.fromInstrumentationArguments(
                    mapOf(
                        CloudflareE2eValidationInstrumentationArguments.tunnelToken to "  $validTunnelToken  ",
                        CloudflareE2eValidationInstrumentationArguments.managementApiToken to "  management-secret  ",
                        CloudflareE2eValidationInstrumentationArguments.managementHostname to
                            "  https://operator:hostname-secret@management.example.test/private  ",
                    ),
                ),
            )

        assertEquals(validTunnelToken, ready.tunnelToken)
        assertEquals("management-secret", ready.managementApiToken)
        assertEquals("https://management.example.test", ready.managementHostname)
        assertFalse(ready.safeSummary.contains("operator"))
        assertFalse(ready.safeSummary.contains("hostname-secret"))
        assertFalse(ready.safeSummary.contains("/private"))
    }

    @Test
    fun `instrumentation argument loader uses only declared Cloudflare e2e arguments`() {
        val config =
            CloudflareE2eValidationConfig.fromInstrumentationArguments(
                mapOf(
                    CloudflareE2eValidationInstrumentationArguments.tunnelToken to "not-a-real-tunnel-token",
                    CloudflareE2eValidationInstrumentationArguments.managementApiToken to "management-secret",
                    "unrelated.secret.argument" to "must-not-appear",
                ),
            )

        val invalid = assertIs<CloudflareE2eValidationConfig.InvalidTunnelToken>(config)

        assertEquals(
            setOf(
                CloudflareE2eValidationInstrumentationArguments.tunnelToken,
                CloudflareE2eValidationInstrumentationArguments.managementApiToken,
                CloudflareE2eValidationInstrumentationArguments.managementHostname,
            ),
            CloudflareE2eValidationInstrumentationArguments.all,
        )
        assertFalse(invalid.safeSummary.contains("not-a-real-tunnel-token"))
        assertFalse(invalid.safeSummary.contains("management-secret"))
        assertFalse(invalid.safeSummary.contains("must-not-appear"))
        assertFalse(invalid.toString().contains("not-a-real-tunnel-token"))
        assertFalse(invalid.toString().contains("management-secret"))
        assertFalse(invalid.toString().contains("must-not-appear"))
    }

    @Test
    fun `android instrumentation receives Cloudflare e2e arguments from local file environment or Gradle property sources`() {
        val repoRoot = repoRoot()
        val appBuild = repoRoot.resolve("app/build.gradle.kts").readText()
        val gitIgnore = repoRoot.resolve(".gitignore").readText()

        assertTrue(appBuild.contains("testInstrumentationRunnerArguments"))
        assertTrue(appBuild.contains("cloudflareTunnelToken"))
        assertTrue(appBuild.contains("cloudflareManagementHostname"))
        assertTrue(appBuild.contains("cloudflareManagementApiToken"))
        assertTrue(appBuild.contains("cellularproxy.e2e.cloudflareTunnelToken"))
        assertTrue(appBuild.contains("e2e.local.properties"))
        assertTrue(appBuild.contains("CELLULARPROXY_E2E_CLOUDFLARE_TUNNEL_TOKEN"))
        assertTrue(appBuild.contains("CELLULARPROXY_E2E_CLOUDFLARE_MANAGEMENT_HOSTNAME"))
        assertTrue(appBuild.contains("CELLULARPROXY_E2E_MANAGEMENT_API_TOKEN"))
        assertTrue(appBuild.contains("gradleProperty(gradleProperty)"))
        assertTrue(appBuild.contains("gradleProperty = \"cellularproxy.e2e.cloudflareTunnelToken\""))
        assertTrue(appBuild.contains("gradleProperty = \"cellularproxy.e2e.cloudflareManagementHostname\""))
        assertTrue(appBuild.contains("gradleProperty = \"cellularproxy.e2e.cloudflareManagementApiToken\""))
        assertTrue(appBuild.contains("environmentVariable(environmentVariable)"))

        assertTrue(gitIgnore.lineSequence().any { it == "e2e.local.properties" })
        assertTrue(gitIgnore.lineSequence().any { it == "*.local.properties" })
        assertTrue(gitIgnore.lineSequence().any { it == ".env" })
        assertTrue(gitIgnore.lineSequence().any { it == ".env.*" })
        assertTrue(gitIgnore.lineSequence().any { it == "secrets.properties" })
    }
}

private const val validTunnelToken =
    "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="

private fun repoRoot() = Path(requireNotNull(System.getProperty("user.dir"))).let { workingDirectory ->
    if (workingDirectory.resolve("settings.gradle.kts").toFile().exists()) {
        workingDirectory
    } else {
        requireNotNull(workingDirectory.parent)
    }
}
