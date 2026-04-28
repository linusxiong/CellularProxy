package com.cellularproxy.app.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CloudflareE2eValidationEvidenceTest {
    @Test
    fun `successful evidence summary is bounded and redacted`() {
        val evidence =
            CloudflareE2eValidationEvidence.success(
                durationMillis = 1_250,
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.Connected,
                httpStatusCode = 200,
            )

        assertEquals(
            "Cloudflare e2e validation succeeded: durationMs=1250, edgeSession=connected, httpStatus=200",
            evidence.safeSummary,
        )
        assertEquals(
            "CloudflareE2eValidationEvidence(status=Success, durationMillis=1250, edgeSessionCategory=connected, httpStatusCode=200, errorClass=null)",
            evidence.toString(),
        )
    }

    @Test
    fun `failed evidence summary records only safe error class`() {
        val evidence =
            CloudflareE2eValidationEvidence.failure(
                durationMillis = 2_000,
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                httpStatusCode = 503,
                errorClass = CloudflareE2eErrorClass.Network,
            )

        assertEquals(
            "Cloudflare e2e validation failed: durationMs=2000, edgeSession=management_api_round_trip, httpStatus=503, errorClass=network",
            evidence.safeSummary,
        )
    }

    @Test
    fun `failure from throwable records only throwable class name`() {
        val evidence =
            CloudflareE2eValidationEvidence.failure(
                durationMillis = 10,
                edgeSessionCategory = null,
                httpStatusCode = null,
                throwable = IllegalStateException("Authorization: Bearer management-secret"),
            )

        assertEquals(
            "Cloudflare e2e validation failed: durationMs=10, edgeSession=not recorded, httpStatus=not recorded, errorClass=IllegalStateException",
            evidence.safeSummary,
        )
        assertFalse(evidence.safeSummary.contains("management-secret"))
        assertFalse(evidence.safeSummary.contains("Authorization"))
    }

    @Test
    fun `evidence rejects negative duration invalid http status and blank throwable class name`() {
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationEvidence.success(
                durationMillis = -1,
                edgeSessionCategory = null,
                httpStatusCode = 200,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CloudflareE2eValidationEvidence.failure(
                durationMillis = 1,
                edgeSessionCategory = null,
                httpStatusCode = 99,
                errorClass = CloudflareE2eErrorClass.Network,
            )
        }
    }
}
