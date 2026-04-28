package com.cellularproxy.app.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CloudflareE2eValidationControllerTest {
    @Test
    fun `disabled or invalid config records failure evidence without invoking validator`() {
        val attemptedConfigs = mutableListOf<CloudflareE2eValidationConfig.Ready>()
        val controller =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = { 1_000L },
                validator = { config ->
                    attemptedConfigs += config
                    CloudflareE2eValidationAttemptResult.Success(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.Connected,
                        httpStatusCode = 200,
                    )
                },
            )

        val disabled = controller.run(CloudflareE2eValidationConfig.Disabled)
        val invalid = controller.run(CloudflareE2eValidationConfig.InvalidTunnelToken)

        assertEquals(emptyList(), attemptedConfigs)
        assertEquals(
            "Cloudflare e2e validation failed: durationMs=0, edgeSession=not recorded, " +
                "httpStatus=not recorded, errorClass=invalid_configuration",
            disabled.safeSummary,
        )
        assertEquals(
            "Cloudflare e2e validation failed: durationMs=0, edgeSession=not recorded, " +
                "httpStatus=not recorded, errorClass=invalid_tunnel_token",
            invalid.safeSummary,
        )
    }

    @Test
    fun `ready config invokes validator only when explicitly run and measures duration`() {
        var now = 10_000L
        val attemptedConfigs = mutableListOf<CloudflareE2eValidationConfig.Ready>()
        val controller =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = { now.also { now += 250L } },
                validator = { config ->
                    attemptedConfigs += config
                    CloudflareE2eValidationAttemptResult.Success(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                        httpStatusCode = 204,
                    )
                },
            )
        val ready =
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = "real-token-value",
                managementApiToken = "management-secret",
                managementHostname = "https://management.example.test",
            )

        assertEquals(emptyList(), attemptedConfigs)

        val evidence = controller.run(ready)

        assertEquals(listOf(ready), attemptedConfigs)
        assertEquals(
            "Cloudflare e2e validation succeeded: durationMs=250, " +
                "edgeSession=management_api_round_trip, httpStatus=204",
            evidence.safeSummary,
        )
        assertFalse(evidence.safeSummary.contains("real-token-value"))
        assertFalse(evidence.safeSummary.contains("management-secret"))
    }

    @Test
    fun `validator failures become redacted failure evidence`() {
        var now = 1_000L
        val controller =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = { now.also { now += 75L } },
                validator = {
                    CloudflareE2eValidationAttemptResult.Failure(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.EdgeUnavailable,
                        httpStatusCode = 503,
                        errorClass = CloudflareE2eErrorClass.Unavailable,
                    )
                },
            )

        val evidence =
            controller.run(
                CloudflareE2eValidationConfig.Ready(
                    tunnelToken = "real-token-value",
                    managementApiToken = null,
                    managementHostname = null,
                ),
            )

        assertEquals(
            "Cloudflare e2e validation failed: durationMs=75, edgeSession=edge_unavailable, " +
                "httpStatus=503, errorClass=unavailable",
            evidence.safeSummary,
        )
        assertFalse(evidence.safeSummary.contains("real-token-value"))
    }

    @Test
    fun `validator exceptions become throwable-class failure evidence without details`() {
        val controller =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = { 0L },
                validator = {
                    error("Authorization: Bearer management-secret")
                },
            )

        val evidence =
            controller.run(
                CloudflareE2eValidationConfig.Ready(
                    tunnelToken = "real-token-value",
                    managementApiToken = "management-secret",
                    managementHostname = "management.example.test",
                ),
            )

        assertEquals(
            "Cloudflare e2e validation failed: durationMs=0, edgeSession=not recorded, " +
                "httpStatus=not recorded, errorClass=IllegalStateException",
            evidence.safeSummary,
        )
        assertFalse(evidence.safeSummary.contains("management-secret"))
        assertFalse(evidence.safeSummary.contains("Authorization"))
        assertFalse(evidence.safeSummary.contains("real-token-value"))
    }

    @Test
    fun `validation attempt results reject invalid http status values`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationAttemptResult.Success(
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.Connected,
                httpStatusCode = 99,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationAttemptResult.Failure(
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.EdgeUnavailable,
                httpStatusCode = 600,
                errorClass = CloudflareE2eErrorClass.Unavailable,
            )
        }
    }
}
