package com.cellularproxy.proxy.management

import com.cellularproxy.proxy.ingress.ProxyIngressStreamPreflightDecision
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.OutputStream

sealed interface ManagementApiStreamExchangeHandlingResult {
    data class Responded(
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

enum class ManagementApiStreamExchangeDisposition {
    Routed,
    RouteRejected,
}

enum class ManagementApiStreamExchangeUnsupportedReason {
    NotManagementRequest,
}

class ManagementApiStreamExchangeHandler(
    private val handler: ManagementApiHandler,
) {
    fun handle(
        accepted: ProxyIngressStreamPreflightDecision.Accepted,
        clientOutput: OutputStream,
    ): ManagementApiStreamExchangeHandlingResult {
        val request = accepted.request as? ParsedProxyRequest.Management
            ?: return ManagementApiStreamExchangeHandlingResult.UnsupportedAcceptedRequest(
                ManagementApiStreamExchangeUnsupportedReason.NotManagementRequest,
            )

        return when (val decision = ManagementApiDispatcher.dispatch(request = request, handler = handler)) {
            is ManagementApiDispatchDecision.Respond ->
                writeResponse(
                    response = decision.response,
                    requiresAuditLog = decision.requiresAuditLog,
                    disposition = ManagementApiStreamExchangeDisposition.Routed,
                    clientOutput = clientOutput,
                )
            is ManagementApiDispatchDecision.Reject ->
                writeResponse(
                    response = decision.response,
                    requiresAuditLog = decision.requiresAuditLog,
                    disposition = ManagementApiStreamExchangeDisposition.RouteRejected,
                    clientOutput = clientOutput,
                )
        }
    }

    private fun writeResponse(
        response: ManagementApiResponse,
        requiresAuditLog: Boolean,
        disposition: ManagementApiStreamExchangeDisposition,
        clientOutput: OutputStream,
    ): ManagementApiStreamExchangeHandlingResult.Responded {
        val bytes = response.toByteArray()
        clientOutput.write(bytes)
        clientOutput.flush()

        return ManagementApiStreamExchangeHandlingResult.Responded(
            statusCode = response.statusCode,
            responseBytesWritten = bytes.size,
            requiresAuditLog = requiresAuditLog,
            disposition = disposition,
        )
    }
}

private val HTTP_STATUS_CODE_RANGE = 100..599
