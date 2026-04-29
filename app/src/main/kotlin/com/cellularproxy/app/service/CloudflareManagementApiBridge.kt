package com.cellularproxy.app.service

import android.content.Context
import android.util.Log
import com.cellularproxy.app.audit.CellularProxyManagementAuditStore
import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.ManagementApiAuditRecord
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
import com.cellularproxy.proxy.management.ManagementApiHandlerException
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import com.cellularproxy.shared.management.ManagementAccessPolicy

class CloudflareManagementApiBridge(
    private val admissionConfig: ProxyRequestAdmissionConfig,
    private val managementHandler: ManagementApiHandler,
    private val recordManagementAudit: (ManagementApiAuditRecord) -> Unit,
) : CloudflareLocalManagementHandler {
    override fun handle(request: CloudflareLocalManagementRequest): CloudflareTunnelResponse {
        val accessDecision = ManagementAccessPolicy.evaluate(request.method, request.originTarget)
        val parsedManagementRequest =
            ParsedProxyRequest.Management(
                method = request.method,
                originTarget = request.originTarget,
                requiresToken = accessDecision.requiresToken,
                requiresAuditLog = accessDecision.requiresAuditLog,
            )

        return when (
            val admission =
                ProxyRequestAdmissionPolicy.evaluate(
                    config = admissionConfig,
                    request =
                        ParsedHttpRequest(
                            request = parsedManagementRequest,
                            headers = request.headers,
                        ),
                )
        ) {
            is ProxyRequestAdmissionDecision.Accepted ->
                dispatch(parsedManagementRequest)
            is ProxyRequestAdmissionDecision.Rejected -> {
                val response = admission.toCloudflareResponse()
                if (accessDecision.requiresAuditLog) {
                    recordManagementAudit(
                        ManagementApiAuditRecord(
                            operation = null,
                            outcome = ManagementApiAuditOutcome.AuthorizationRejected,
                            statusCode = response.statusCode,
                            disposition = null,
                        ),
                    )
                }
                response
            }
        }
    }

    override fun toString(): String = "CloudflareManagementApiBridge(admissionConfig=[REDACTED], managementHandler=[REDACTED])"

    private fun dispatch(request: ParsedProxyRequest.Management): CloudflareTunnelResponse = try {
        when (val decision = ManagementApiDispatcher.dispatch(request = request, handler = managementHandler)) {
            is ManagementApiDispatchDecision.Respond ->
                decision.response.toCloudflareResponse().also { response ->
                    if (decision.requiresAuditLog) {
                        recordManagementAudit(
                            ManagementApiAuditRecord(
                                operation = decision.operation,
                                outcome = ManagementApiAuditOutcome.Responded,
                                statusCode = response.statusCode,
                                disposition = ManagementApiStreamExchangeDisposition.Routed,
                            ),
                        )
                    }
                    decision.response.notifyResponseSent()
                }
            is ManagementApiDispatchDecision.Reject ->
                decision.response.toCloudflareResponse().also { response ->
                    if (decision.requiresAuditLog) {
                        recordManagementAudit(
                            ManagementApiAuditRecord(
                                operation = request.toAttemptedHighImpactOperationOrNull(),
                                outcome = ManagementApiAuditOutcome.RouteRejected,
                                statusCode = response.statusCode,
                                disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                            ),
                        )
                    }
                    decision.response.notifyResponseSent()
                }
        }
    } catch (failure: ManagementApiHandlerException) {
        if (failure.requiresAuditLog) {
            recordManagementAudit(
                ManagementApiAuditRecord(
                    operation = failure.operation,
                    outcome = ManagementApiAuditOutcome.HandlerFailed,
                    statusCode = null,
                    disposition = null,
                ),
            )
        }
        throw failure
    }

    private fun ProxyRequestAdmissionDecision.Rejected.toCloudflareResponse(): CloudflareTunnelResponse = when (
        val decision =
            ProxyErrorResponseMapper.map(
                ProxyServerFailure.Admission(reason = reason),
            )
    ) {
        is ProxyErrorResponseDecision.Emit ->
            decision.response.toCloudflareResponse()
        ProxyErrorResponseDecision.Suppress ->
            CloudflareTunnelResponse.empty(statusCode = 500)
    }

    companion object {
        fun create(
            context: Context,
            admissionConfig: ProxyRequestAdmissionConfig,
            managementHandler: ManagementApiHandler,
            reportManagementAuditFailure: (Exception) -> Unit = ::logManagementAuditFailure,
        ): CloudflareManagementApiBridge {
            val auditLog = CellularProxyManagementAuditStore.managementApiAuditLog(context.applicationContext)
            return CloudflareManagementApiBridge(
                admissionConfig = admissionConfig,
                managementHandler = managementHandler,
                recordManagementAudit =
                    nonFatalManagementAuditRecorder(
                        recordManagementAudit = auditLog::record,
                        reportManagementAuditFailure = reportManagementAuditFailure,
                    ),
            )
        }
    }
}

internal fun nonFatalManagementAuditRecorder(
    recordManagementAudit: (ManagementApiAuditRecord) -> Unit,
    reportManagementAuditFailure: (Exception) -> Unit = ::logManagementAuditFailure,
): (ManagementApiAuditRecord) -> Unit = { auditRecord ->
    try {
        recordManagementAudit(auditRecord)
    } catch (exception: Exception) {
        reportManagementAuditFailure(exception)
    }
}

private fun ManagementApiResponse.toCloudflareResponse(): CloudflareTunnelResponse = CloudflareTunnelResponse(
    statusCode = statusCode,
    reasonPhrase = reasonPhrase,
    headers = headers,
    body = body,
)

private fun ParsedProxyRequest.Management.toAttemptedHighImpactOperationOrNull(): ManagementApiOperation? {
    val path = originTarget.substringBefore('?').substringBefore('#')
    return when (method to path) {
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/cloudflare/start" ->
            ManagementApiOperation.CloudflareStart
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/cloudflare/stop" ->
            ManagementApiOperation.CloudflareStop
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/cloudflare/reconnect" ->
            ManagementApiOperation.CloudflareReconnect
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/rotate/mobile-data" ->
            ManagementApiOperation.RotateMobileData
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/rotate/airplane-mode" ->
            ManagementApiOperation.RotateAirplaneMode
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/service/stop" ->
            ManagementApiOperation.ServiceStop
        com.cellularproxy.shared.management.HttpMethod.Post to "/api/service/restart" ->
            ManagementApiOperation.ServiceRestart
        else -> null
    }
}

private fun logManagementAuditFailure(exception: Exception) {
    Log.w(MANAGEMENT_AUDIT_LOG_TAG, "Failed to persist management API audit record", exception)
}

private const val MANAGEMENT_AUDIT_LOG_TAG = "CellularProxyAudit"

private fun ProxyErrorResponse.toCloudflareResponse(): CloudflareTunnelResponse = CloudflareTunnelResponse(
    statusCode = statusCode,
    reasonPhrase = reasonPhrase,
    headers = headers,
    body = body,
)
