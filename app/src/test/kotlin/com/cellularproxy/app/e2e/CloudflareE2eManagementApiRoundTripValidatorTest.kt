package com.cellularproxy.app.e2e

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `default transport does not follow management status redirects after attaching authorization`() {
        val server = ServerSocket(0).apply { soTimeout = 2_000 }
        val redirectedRequests = AtomicInteger(0)
        val serverThread =
            Thread {
                repeat(2) {
                    try {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val requestLine = reader.readLine().orEmpty()
                            generateSequence { reader.readLine() }
                                .takeWhile { it.isNotEmpty() }
                                .forEach { }
                            if (requestLine.startsWith("GET /redirected ")) {
                                redirectedRequests.incrementAndGet()
                            }
                            val response =
                                if (requestLine.startsWith("GET /api/status ")) {
                                    "HTTP/1.1 302 Found\r\n" +
                                        "Location: http://127.0.0.1:${server.localPort}/redirected\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n"
                                } else {
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n"
                                }
                            socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        }
                    } catch (_: SocketTimeoutException) {
                        return@Thread
                    }
                }
            }.also(Thread::start)

        try {
            val result =
                CloudflareE2eManagementApiRoundTripValidator()
                    .validate(
                        CloudflareE2eValidationConfig.Ready(
                            tunnelToken = validTunnelToken,
                            managementApiToken = "management-secret",
                            managementHostname = "http://127.0.0.1:${server.localPort}",
                        ),
                    )

            assertEquals(
                CloudflareE2eValidationAttemptResult.Failure(
                    edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
                    httpStatusCode = 302,
                    errorClass = CloudflareE2eErrorClass.Network,
                ),
                result,
            )
            assertEquals(0, redirectedRequests.get())
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }
}

private fun readyConfig(): CloudflareE2eValidationConfig.Ready = CloudflareE2eValidationConfig.Ready(
    tunnelToken = validTunnelToken,
    managementApiToken = "management-secret",
    managementHostname = "https://management.example.test",
)

private const val validTunnelToken =
    "eyJhIjoiYWNjb3VudC10YWciLCJzIjoiQVFJREJBVUdCd2dKQ2dzTURRNFBFQkVTRXhRVkZoY1lHUm9iSEIwZUh5QT0iLCJ0IjoiMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwIn0="
