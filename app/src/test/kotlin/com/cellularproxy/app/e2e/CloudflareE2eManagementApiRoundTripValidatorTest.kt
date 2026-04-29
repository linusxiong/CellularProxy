package com.cellularproxy.app.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloudflareE2eManagementApiRoundTripValidatorTest {
    @Test
    fun `ready config sends authenticated management status round trip`() {
        val requests = mutableListOf<CloudflareE2eManagementApiRoundTripRequest>()
        val validator =
            CloudflareE2eManagementApiRoundTripValidator(
                transport = { request ->
                    requests += request
                    CloudflareE2eManagementApiRoundTripResponse(httpStatusCode = 204)
                },
            )

        val result =
            validator.validate(
                CloudflareE2eValidationConfig.Ready(
                    tunnelToken = validTunnelToken,
                    managementApiToken = "management-secret",
                    managementHostname = "management.example.test",
                ),
            )

        assertEquals(
            CloudflareE2eValidationAttemptResult.Success(
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                httpStatusCode = 204,
            ),
            result,
        )
        assertEquals(
            listOf(
                CloudflareE2eManagementApiRoundTripRequest(
                    url = "https://management.example.test/api/status",
                    authorizationHeader = "Bearer management-secret",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `url construction preserves sanitized url-like hostname when URI host is unavailable`() {
        val requests = mutableListOf<CloudflareE2eManagementApiRoundTripRequest>()
        val validator =
            CloudflareE2eManagementApiRoundTripValidator(
                transport = { request ->
                    requests += request
                    CloudflareE2eManagementApiRoundTripResponse(httpStatusCode = 200)
                },
            )

        validator.validate(
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = validTunnelToken,
                managementApiToken = "management-secret",
                managementHostname = "https://management_example.test:8443",
            ),
        )

        assertEquals(
            "https://management_example.test:8443/api/status",
            requests.single().url,
        )
    }

    @Test
    fun `missing management hostname or token fails without sending a request`() {
        val requests = mutableListOf<CloudflareE2eManagementApiRoundTripRequest>()
        val validator =
            CloudflareE2eManagementApiRoundTripValidator(
                transport = { request ->
                    requests += request
                    CloudflareE2eManagementApiRoundTripResponse(httpStatusCode = 200)
                },
            )

        listOf(
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = validTunnelToken,
                managementApiToken = "management-secret",
                managementHostname = null,
            ),
            CloudflareE2eValidationConfig.Ready(
                tunnelToken = validTunnelToken,
                managementApiToken = null,
                managementHostname = "management.example.test",
            ),
        ).forEach { config ->
            assertEquals(
                CloudflareE2eValidationAttemptResult.Failure(
                    edgeSessionCategory = null,
                    httpStatusCode = null,
                    errorClass = CloudflareE2eErrorClass.InvalidConfiguration,
                ),
                validator.validate(config),
            )
        }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `unsupported management hostname scheme fails as invalid config without sending a request`() {
        val requests = mutableListOf<CloudflareE2eManagementApiRoundTripRequest>()
        val validator =
            CloudflareE2eManagementApiRoundTripValidator(
                transport = { request ->
                    requests += request
                    CloudflareE2eManagementApiRoundTripResponse(httpStatusCode = 200)
                },
            )

        val result =
            validator.validate(
                CloudflareE2eValidationConfig.Ready(
                    tunnelToken = validTunnelToken,
                    managementApiToken = "management-secret",
                    managementHostname = "file://management.example.test",
                ),
            )

        assertEquals(
            CloudflareE2eValidationAttemptResult.Failure(
                edgeSessionCategory = null,
                httpStatusCode = null,
                errorClass = CloudflareE2eErrorClass.InvalidConfiguration,
            ),
            result,
        )
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `http response statuses map to bounded e2e evidence classes`() {
        val statuses =
            mapOf(
                200 to
                    CloudflareE2eValidationAttemptResult.Success(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                        httpStatusCode = 200,
                    ),
                401 to
                    CloudflareE2eValidationAttemptResult.Failure(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                        httpStatusCode = 401,
                        errorClass = CloudflareE2eErrorClass.Unauthorized,
                    ),
                503 to
                    CloudflareE2eValidationAttemptResult.Failure(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                        httpStatusCode = 503,
                        errorClass = CloudflareE2eErrorClass.Unavailable,
                    ),
                502 to
                    CloudflareE2eValidationAttemptResult.Failure(
                        edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                        httpStatusCode = 502,
                        errorClass = CloudflareE2eErrorClass.Network,
                    ),
            )

        statuses.forEach { (status, expected) ->
            val validator =
                CloudflareE2eManagementApiRoundTripValidator(
                    transport = { CloudflareE2eManagementApiRoundTripResponse(httpStatusCode = status) },
                )

            assertEquals(expected, validator.validate(readyConfig()))
        }
    }

    @Test
    fun `transport exception maps to network failure without leaking secrets`() {
        val validator =
            CloudflareE2eManagementApiRoundTripValidator(
                transport = {
                    throw IllegalStateException("Authorization: Bearer management-secret")
                },
            )

        val result = validator.validate(readyConfig())
        val evidence =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = { 0L },
                validator = { result },
            ).run(readyConfig())

        assertEquals(
            CloudflareE2eValidationAttemptResult.Failure(
                edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                httpStatusCode = null,
                errorClass = CloudflareE2eErrorClass.Network,
            ),
            result,
        )
        assertFalse(evidence.safeSummary.contains("management-secret"))
        assertFalse(evidence.safeSummary.contains(validTunnelToken))
        assertFalse(evidence.safeSummary.contains("Authorization"))
    }
}

private fun readyConfig(): CloudflareE2eValidationConfig.Ready = CloudflareE2eValidationConfig.Ready(
    tunnelToken = validTunnelToken,
    managementApiToken = "management-secret",
    managementHostname = "https://management.example.test",
)

private const val validTunnelToken =
    "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="
