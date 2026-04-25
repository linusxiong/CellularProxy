package com.cellularproxy.proxy.ingress

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionDecision
import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionPolicy
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionDecision
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionPolicy
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionRejectionReason
import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockParseResult
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockParser
import com.cellularproxy.proxy.protocol.ParsedHttpRequest
import com.cellularproxy.proxy.protocol.ParsedProxyRequest

data class ProxyIngressPreflightConfig(
    val connectionLimit: ConnectionLimitAdmissionConfig,
    val requestAdmission: ProxyRequestAdmissionConfig,
    val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
) {
    init {
        require(maxHeaderBytes > 0) { "Maximum header bytes must be positive" }
    }
}

sealed interface ProxyIngressPreflightDecision {
    data class Accepted(
        val httpRequest: ParsedHttpRequest,
        val activeConnectionsAfterAdmission: Long,
        val requiresAuditLog: Boolean,
    ) : ProxyIngressPreflightDecision {
        val request: ParsedProxyRequest
            get() = httpRequest.request

        override fun toString(): String =
            "Accepted(request=$request, " +
                "activeConnectionsAfterAdmission=$activeConnectionsAfterAdmission, " +
                "requiresAuditLog=$requiresAuditLog)"
    }

    data class Rejected(
        val failure: ProxyServerFailure,
        val response: ProxyErrorResponseDecision,
        val requiresAuditLog: Boolean,
    ) : ProxyIngressPreflightDecision
}

object ProxyIngressPreflight {
    fun evaluate(
        config: ProxyIngressPreflightConfig,
        activeConnections: Long,
        headerBlock: String,
    ): ProxyIngressPreflightDecision {
        val capacity = when (
            val decision = ConnectionLimitAdmissionPolicy.evaluate(
                config = config.connectionLimit,
                activeConnections = activeConnections,
            )
        ) {
            is ConnectionLimitAdmissionDecision.Accepted -> decision
            is ConnectionLimitAdmissionDecision.Rejected -> {
                val failure = ProxyServerFailure.ConnectionLimit(decision.reason)
                return rejected(failure = failure, requiresAuditLog = false)
            }
        }

        val parsed = when (
            val result = HttpRequestHeaderBlockParser.parse(
                headerBlock = headerBlock,
                maxHeaderBytes = config.maxHeaderBytes,
            )
        ) {
            is HttpRequestHeaderBlockParseResult.Accepted -> result.request
            is HttpRequestHeaderBlockParseResult.Rejected -> {
                val failure = ProxyServerFailure.HeaderBlockParse(
                    reason = result.reason,
                    requestLineRejectionReason = result.requestLineRejectionReason,
                )
                return rejected(failure = failure, requiresAuditLog = false)
            }
        }

        return when (
            val decision = ProxyRequestAdmissionPolicy.evaluate(
                config = config.requestAdmission,
                request = parsed,
            )
        ) {
            is ProxyRequestAdmissionDecision.Accepted ->
                ProxyIngressPreflightDecision.Accepted(
                    httpRequest = parsed,
                    activeConnectionsAfterAdmission = capacity.activeConnectionsAfterAdmission,
                    requiresAuditLog = decision.requiresAuditLog,
                )
            is ProxyRequestAdmissionDecision.Rejected -> {
                val failure = ProxyServerFailure.Admission(decision.reason)
                rejected(
                    failure = failure,
                    requiresAuditLog = decision.reason is ProxyRequestAdmissionRejectionReason.ManagementAuthorization,
                )
            }
        }
    }

    private fun rejected(
        failure: ProxyServerFailure,
        requiresAuditLog: Boolean,
    ): ProxyIngressPreflightDecision.Rejected =
        ProxyIngressPreflightDecision.Rejected(
            failure = failure,
            response = ProxyErrorResponseMapper.map(failure),
            requiresAuditLog = requiresAuditLog,
        )
}

private const val DEFAULT_MAX_HEADER_BYTES = 16 * 1024
