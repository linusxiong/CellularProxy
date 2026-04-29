package com.cellularproxy.app.e2e

import java.net.HttpURLConnection
import java.net.URL

class CloudflareE2eManagementApiRoundTripValidator(
    private val transport: (CloudflareE2eManagementApiRoundTripRequest) -> CloudflareE2eManagementApiRoundTripResponse =
        ::sendManagementApiStatusRequest,
) {
    fun validate(
        config: CloudflareE2eValidationConfig.Ready,
    ): CloudflareE2eValidationAttemptResult {
        val managementHostname = config.managementHostname ?: return invalidConfiguration()
        val managementApiToken = config.managementApiToken ?: return invalidConfiguration()
        val managementStatusUrl = managementHostname.toManagementStatusUrlOrNull() ?: return invalidConfiguration()
        val request =
            CloudflareE2eManagementApiRoundTripRequest(
                url = managementStatusUrl,
                authorizationHeader = "$BEARER_SCHEME $managementApiToken",
            )

        return try {
            transport(request).toAttemptResult()
        } catch (_: Exception) {
            networkFailure(httpStatusCode = null)
        }
    }
}

data class CloudflareE2eManagementApiRoundTripRequest(
    val url: String,
    val authorizationHeader: String,
) {
    override fun toString(): String = "CloudflareE2eManagementApiRoundTripRequest(" +
        "url=$url, " +
        "authorizationHeader=[REDACTED])"
}

data class CloudflareE2eManagementApiRoundTripResponse(
    val httpStatusCode: Int,
) {
    init {
        require(httpStatusCode in HTTP_STATUS_CODE_RANGE) {
            "httpStatusCode must be a valid HTTP status code"
        }
    }
}

private fun CloudflareE2eManagementApiRoundTripResponse.toAttemptResult(): CloudflareE2eValidationAttemptResult = when (
    httpStatusCode
) {
    in HTTP_SUCCESS_STATUS_CODES ->
        CloudflareE2eValidationAttemptResult.Success(
            edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
            httpStatusCode = httpStatusCode,
        )
    HTTP_UNAUTHORIZED_STATUS_CODE ->
        CloudflareE2eValidationAttemptResult.Failure(
            edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
            httpStatusCode = httpStatusCode,
            errorClass = CloudflareE2eErrorClass.Unauthorized,
        )
    HTTP_SERVICE_UNAVAILABLE_STATUS_CODE ->
        CloudflareE2eValidationAttemptResult.Failure(
            edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
            httpStatusCode = httpStatusCode,
            errorClass = CloudflareE2eErrorClass.Unavailable,
        )
    else -> networkFailure(httpStatusCode = httpStatusCode)
}

private fun sendManagementApiStatusRequest(
    request: CloudflareE2eManagementApiRoundTripRequest,
): CloudflareE2eManagementApiRoundTripResponse {
    val connection = URL(request.url).openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = DEFAULT_TIMEOUT_MILLIS
        connection.readTimeout = DEFAULT_TIMEOUT_MILLIS
        connection.setRequestProperty(AUTHORIZATION_HEADER, request.authorizationHeader)
        CloudflareE2eManagementApiRoundTripResponse(connection.responseCode)
    } finally {
        connection.disconnect()
    }
}

private fun String.toManagementStatusUrlOrNull(): String? {
    if ("://" in this && !startsWith("https://") && !startsWith("http://")) {
        return null
    }
    val value =
        if ("://" in this) {
            this
        } else {
            "https://$this"
        }
    return "${value.trimEnd('/')}$MANAGEMENT_STATUS_PATH"
}

private fun invalidConfiguration(): CloudflareE2eValidationAttemptResult.Failure = CloudflareE2eValidationAttemptResult.Failure(
    edgeSessionCategory = null,
    httpStatusCode = null,
    errorClass = CloudflareE2eErrorClass.InvalidConfiguration,
)

private fun networkFailure(httpStatusCode: Int?): CloudflareE2eValidationAttemptResult.Failure = CloudflareE2eValidationAttemptResult.Failure(
    edgeSessionCategory = CloudflareE2eEdgeSessionCategory.ManagementApiRoundTrip,
    httpStatusCode = httpStatusCode,
    errorClass = CloudflareE2eErrorClass.Network,
)

private const val MANAGEMENT_STATUS_PATH = "/api/status"
private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_SCHEME = "Bearer"
private const val DEFAULT_TIMEOUT_MILLIS = 10_000
private const val HTTP_UNAUTHORIZED_STATUS_CODE = 401
private const val HTTP_SERVICE_UNAVAILABLE_STATUS_CODE = 503
private val HTTP_STATUS_CODE_RANGE = 100..599
private val HTTP_SUCCESS_STATUS_CODES = 200..299
