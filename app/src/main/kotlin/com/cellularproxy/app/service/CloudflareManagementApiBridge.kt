package com.cellularproxy.app.service

import com.cellularproxy.cloudflare.CloudflareLocalManagementHandler
import com.cellularproxy.cloudflare.CloudflareLocalManagementRequest
import com.cellularproxy.cloudflare.CloudflareTunnelResponse
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionDecision
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionPolicy
import com.cellularproxy.proxy.errors.ProxyErrorResponse
import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.management.ManagementApiDispatchDecision
import com.cellularproxy.proxy.management.ManagementApiDispatcher
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.ManagementAccessPolicy

class CloudflareManagementApiBridge(
    private val admissionConfig: ProxyRequestAdmissionConfig,
    private val managementHandler: ManagementApiHandler,
) : CloudflareLocalManagementHandler {
    override fun handle(request: CloudflareLocalManagementRequest): CloudflareTunnelResponse {
        val accessDecision = ManagementAccessPolicy.evaluate(request.method, request.originTarget)
        val parsedManagementRequest = ParsedProxyRequest.Management(
            method = request.method,
            originTarget = request.originTarget,
            requiresToken = accessDecision.requiresToken,
            requiresAuditLog = accessDecision.requiresAuditLog,
        )

        return when (
            val admission = ProxyRequestAdmissionPolicy.evaluate(
                config = admissionConfig,
                request = ParsedHttpRequest(
                    request = parsedManagementRequest,
                    headers = request.headers,
                ),
            )
        ) {
            is ProxyRequestAdmissionDecision.Accepted ->
                dispatch(parsedManagementRequest)
            is ProxyRequestAdmissionDecision.Rejected ->
                admission.toCloudflareResponse()
        }
    }

    override fun toString(): String =
        "CloudflareManagementApiBridge(admissionConfig=[REDACTED], managementHandler=[REDACTED])"

    private fun dispatch(request: ParsedProxyRequest.Management): CloudflareTunnelResponse =
        when (val decision = ManagementApiDispatcher.dispatch(request = request, handler = managementHandler)) {
            is ManagementApiDispatchDecision.Respond ->
                decision.response.toCloudflareResponse()
            is ManagementApiDispatchDecision.Reject ->
                decision.response.toCloudflareResponse()
        }

    private fun ProxyRequestAdmissionDecision.Rejected.toCloudflareResponse(): CloudflareTunnelResponse =
        when (
            val decision = ProxyErrorResponseMapper.map(
                ProxyServerFailure.Admission(reason = reason),
            )
        ) {
            is ProxyErrorResponseDecision.Emit ->
                decision.response.toCloudflareResponse()
            ProxyErrorResponseDecision.Suppress ->
                CloudflareTunnelResponse.empty(statusCode = 500)
        }
}

private fun ManagementApiResponse.toCloudflareResponse(): CloudflareTunnelResponse =
    CloudflareTunnelResponse(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = headers,
        body = body,
    )

private fun ProxyErrorResponse.toCloudflareResponse(): CloudflareTunnelResponse =
    CloudflareTunnelResponse(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        headers = headers,
        body = body,
    )
