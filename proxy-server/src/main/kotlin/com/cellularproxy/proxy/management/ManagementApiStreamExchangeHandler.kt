package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.OutputStream

sealed interface ManagementApiStreamExchangeHandlingResult {
    data class Responded(
        val operation: ManagementApiOperation?,
        val statusCode: Int,
        val responseBytesWritten: Int,
        val requiresAuditLog: Boolean,
        val disposition: ManagementApiStreamExchangeDisposition,
    ) : ManagementApiStreamExchangeHandlingResult {
        init {
            require(statusCode in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" }
            require(responseBytesWritten >= 0) { "Response bytes written must be non-negative" }
        }
    }

    data class UnsupportedAcceptedRequest(
        val reason: ManagementApiStreamExchangeUnsupportedReason,
    ) : ManagementApiStreamExchangeHandlingResult
}

enum class ManagementApiStreamAuditOutcome {
    Responded,
    RouteRejected,
    HandlerFailed,
    AuthorizationRejected,
}

data class ManagementApiStreamAuditEvent(
    val operation: ManagementApiOperation?,
    val outcome: ManagementApiStreamAuditOutcome,
    val statusCode: Int?,
    val disposition: ManagementApiStreamExchangeDisposition?,
) {
    init {
        statusCode?.let { require(it in HTTP_STATUS_CODE_RANGE) { "HTTP status code must be in 100..599" } }
        when (outcome) {
            ManagementApiStreamAuditOutcome.Responded -> {
                require(operation != null) { "Responded management audit events require an operation" }
                require(statusCode != null) { "Responded management audit events require a status code" }
                require(disposition == ManagementApiStreamExchangeDisposition.Routed) {
                    "Responded management audit events require routed disposition"
                }
            }
            ManagementApiStreamAuditOutcome.RouteRejected -> {
                require(statusCode != null) { "Route-rejected management audit events require a status code" }
                require(disposition == ManagementApiStreamExchangeDisposition.RouteRejected) {
                    "Route-rejected management audit events require route-rejected disposition"
                }
            }
            ManagementApiStreamAuditOutcome.HandlerFailed -> {
                require(operation != null) { "Handler-failed management audit events require an operation" }
                require(statusCode == null) { "Handler-failed management audit events cannot have a status code" }
                require(disposition == null) { "Handler-failed management audit events cannot have a disposition" }
            }
            ManagementApiStreamAuditOutcome.AuthorizationRejected -> {
                require(operation == null) { "Authorization-rejected management audit events cannot have an operation" }
                require(statusCode != null) { "Authorization-rejected management audit events require a status code" }
                require(disposition == null) { "Authorization-rejected management audit events cannot have a disposition" }
            }
        }
    }
}

enum class ManagementApiStreamExchangeDisposition {
    Routed,
    RouteRejected,
}

enum class ManagementApiStreamExchangeUnsupportedReason {
    NotManagementRequest,
}

class ManagementApiStreamExchangeHandler(
    private val handler: ManagementApiHandler,
    private val recordManagementAudit: (ManagementApiStreamAuditEvent) -> Unit = {},
) {
    fun handle(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientOutput: OutputStream,
    ): ManagementApiStreamExchangeHandlingResult {
        val request =
            accepted.request as? ParsedProxyRequest.Management
                ?: return ManagementApiStreamExchangeHandlingResult.UnsupportedAcceptedRequest(
                    ManagementApiStreamExchangeUnsupportedReason.NotManagementRequest,
                )

        return try {
            when (val decision = ManagementApiDispatcher.dispatch(request = request, handler = handler)) {
                is ManagementApiDispatchDecision.Respond ->
                    writeResponse(
                        operation = decision.operation,
                        response = decision.response,
                        requiresAuditLog = decision.requiresAuditLog,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                        clientOutput = clientOutput,
                    )
                is ManagementApiDispatchDecision.Reject ->
                    writeResponse(
                        operation = request.toAttemptedHighImpactOperationOrNull(),
                        response = decision.response,
                        requiresAuditLog = decision.requiresAuditLog,
                        disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                        clientOutput = clientOutput,
                    )
            }
        } catch (failure: ManagementApiHandlerException) {
            if (failure.requiresAuditLog) {
                recordManagementAuditSafely(
                    ManagementApiStreamAuditEvent(
                        operation = failure.operation,
                        outcome = ManagementApiStreamAuditOutcome.HandlerFailed,
                        statusCode = null,
                        disposition = null,
                    ),
                )
            }
            throw failure
        }
    }

    private fun writeResponse(
        operation: ManagementApiOperation?,
        response: ManagementApiResponse,
        requiresAuditLog: Boolean,
        disposition: ManagementApiStreamExchangeDisposition,
        clientOutput: OutputStream,
    ): ManagementApiStreamExchangeHandlingResult.Responded {
        val bytes = response.toByteArray()
        val result =
            ManagementApiStreamExchangeHandlingResult.Responded(
                operation = operation,
                statusCode = response.statusCode,
                responseBytesWritten = bytes.size,
                requiresAuditLog = requiresAuditLog,
                disposition = disposition,
            )
        if (requiresAuditLog) {
            recordManagementAuditSafely(result.toAuditEvent())
        }

        clientOutput.write(bytes)
        clientOutput.flush()
        response.notifyResponseSent()

        return result
    }

    private fun recordManagementAuditSafely(event: ManagementApiStreamAuditEvent) {
        try {
            recordManagementAudit(event)
        } catch (_: Exception) {
            // Audit sink failures must not replace management command responses or handler failures.
        }
    }
}

private fun ManagementApiStreamExchangeHandlingResult.Responded.toAuditEvent(): ManagementApiStreamAuditEvent = ManagementApiStreamAuditEvent(
    operation = operation,
    outcome =
        when (disposition) {
            ManagementApiStreamExchangeDisposition.Routed -> ManagementApiStreamAuditOutcome.Responded
            ManagementApiStreamExchangeDisposition.RouteRejected -> ManagementApiStreamAuditOutcome.RouteRejected
        },
    statusCode = statusCode,
    disposition = disposition,
)

internal fun ParsedProxyRequest.Management.toAttemptedHighImpactOperationOrNull(): ManagementApiOperation? {
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

private val HTTP_STATUS_CODE_RANGE = 100..599
