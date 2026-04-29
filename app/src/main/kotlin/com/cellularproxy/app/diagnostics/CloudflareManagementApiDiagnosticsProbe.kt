package com.cellularproxy.app.diagnostics

import com.cellularproxy.cloudflare.CloudflareLocalManagementHandler
import com.cellularproxy.cloudflare.CloudflareTunnelIngressExchangeHandler
import com.cellularproxy.cloudflare.CloudflareTunnelIngressResult
import com.cellularproxy.shared.management.HttpMethod

class CloudflareManagementApiDiagnosticsProbe(
    private val managementApiToken: () -> String,
    private val localManagementHandler: CloudflareLocalManagementHandler,
) {
    fun run(): CloudflareManagementApiProbeResult {
        val token = managementApiToken().trim()
        if (token.isEmpty()) {
            return CloudflareManagementApiProbeResult.NotConfigured
        }

        return try {
            val ingressResult =
                CloudflareTunnelIngressExchangeHandler.handle(
                    method = HttpMethod.Get,
                    target = MANAGEMENT_STATUS_PATH,
                    requestHeaders = mapOf(AUTHORIZATION_HEADER to listOf("$BEARER_SCHEME $token")),
                    localManagementHandler = localManagementHandler,
                )
            ingressResult.toProbeResult()
        } catch (_: Exception) {
            CloudflareManagementApiProbeResult.Error
        }
    }

    override fun toString(): String = "CloudflareManagementApiDiagnosticsProbe(managementApiToken=[REDACTED])"

    private fun CloudflareTunnelIngressResult.toProbeResult(): CloudflareManagementApiProbeResult = when (response.statusCode) {
        in HTTP_SUCCESS_STATUS_CODES -> CloudflareManagementApiProbeResult.Authenticated
        HTTP_UNAUTHORIZED_STATUS_CODE -> CloudflareManagementApiProbeResult.Unauthorized
        HTTP_SERVICE_UNAVAILABLE_STATUS_CODE -> CloudflareManagementApiProbeResult.Unavailable
        else -> CloudflareManagementApiProbeResult.Error
    }
}

private const val MANAGEMENT_STATUS_PATH = "/api/status"
private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_SCHEME = "Bearer"
private const val HTTP_UNAUTHORIZED_STATUS_CODE = 401
private const val HTTP_SERVICE_UNAVAILABLE_STATUS_CODE = 503
private val HTTP_SUCCESS_STATUS_CODES = 200..299
