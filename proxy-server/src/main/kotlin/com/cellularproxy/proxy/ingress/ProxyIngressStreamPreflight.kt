package com.cellularproxy.proxy.ingress

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionDecision
import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionPolicy
import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.protocol.HttpHeaderBlockStreamReadResult
import com.cellularproxy.proxy.protocol.HttpHeaderBlockStreamReader
import com.cellularproxy.proxy.protocol.HttpRequestHeaderBlockRejectionReason
import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.io.InputStream

sealed interface ProxyIngressStreamPreflightDecision {
    data class Accepted(
        val request: ParsedProxyRequest,
        val activeConnectionsAfterAdmission: Long,
        val requiresAuditLog: Boolean,
        val headerBytesRead: Int,
    ) : ProxyIngressStreamPreflightDecision

    data class Rejected(
        val failure: ProxyServerFailure,
        val response: ProxyErrorResponseDecision,
        val requiresAuditLog: Boolean,
        val headerBytesRead: Int,
    ) : ProxyIngressStreamPreflightDecision
}

object ProxyIngressStreamPreflight {
    fun evaluate(
        config: ProxyIngressPreflightConfig,
        activeConnections: Long,
        input: InputStream,
    ): ProxyIngressStreamPreflightDecision {
        when (
            val decision = ConnectionLimitAdmissionPolicy.evaluate(
                config = config.connectionLimit,
                activeConnections = activeConnections,
            )
        ) {
            is ConnectionLimitAdmissionDecision.Accepted -> Unit
            is ConnectionLimitAdmissionDecision.Rejected -> {
                val failure = ProxyServerFailure.ConnectionLimit(decision.reason)
                return rejected(
                    failure = failure,
                    requiresAuditLog = false,
                    headerBytesRead = 0,
                )
            }
        }

        return when (
            val readResult = HttpHeaderBlockStreamReader.read(
                input = input,
                maxHeaderBytes = config.maxHeaderBytes,
            )
        ) {
            is HttpHeaderBlockStreamReadResult.Completed ->
                mapHeaderBlockPreflight(
                    config = config,
                    activeConnections = activeConnections,
                    headerBlock = readResult.headerBlock,
                    headerBytesRead = readResult.bytesRead,
                )
            is HttpHeaderBlockStreamReadResult.Incomplete ->
                rejected(
                    failure = ProxyServerFailure.HeaderBlockParse(
                        reason = HttpRequestHeaderBlockRejectionReason.IncompleteHeaderBlock,
                    ),
                    requiresAuditLog = false,
                    headerBytesRead = readResult.bytesRead,
                )
            is HttpHeaderBlockStreamReadResult.HeaderBlockTooLarge ->
                rejected(
                    failure = ProxyServerFailure.HeaderBlockParse(
                        reason = HttpRequestHeaderBlockRejectionReason.HeaderBlockTooLarge,
                    ),
                    requiresAuditLog = false,
                    headerBytesRead = readResult.bytesRead,
                )
            is HttpHeaderBlockStreamReadResult.MalformedHeaderEncoding ->
                rejected(
                    failure = ProxyServerFailure.HeaderBlockParse(
                        reason = HttpRequestHeaderBlockRejectionReason.MalformedHeaderEncoding,
                    ),
                    requiresAuditLog = false,
                    headerBytesRead = readResult.bytesRead,
                )
        }
    }

    private fun mapHeaderBlockPreflight(
        config: ProxyIngressPreflightConfig,
        activeConnections: Long,
        headerBlock: String,
        headerBytesRead: Int,
    ): ProxyIngressStreamPreflightDecision =
        when (
            val decision = ProxyIngressPreflight.evaluate(
                config = config,
                activeConnections = activeConnections,
                headerBlock = headerBlock,
            )
        ) {
            is ProxyIngressPreflightDecision.Accepted ->
                ProxyIngressStreamPreflightDecision.Accepted(
                    request = decision.request,
                    activeConnectionsAfterAdmission = decision.activeConnectionsAfterAdmission,
                    requiresAuditLog = decision.requiresAuditLog,
                    headerBytesRead = headerBytesRead,
                )
            is ProxyIngressPreflightDecision.Rejected ->
                ProxyIngressStreamPreflightDecision.Rejected(
                    failure = decision.failure,
                    response = decision.response,
                    requiresAuditLog = decision.requiresAuditLog,
                    headerBytesRead = headerBytesRead,
                )
        }

    private fun rejected(
        failure: ProxyServerFailure,
        requiresAuditLog: Boolean,
        headerBytesRead: Int,
    ): ProxyIngressStreamPreflightDecision.Rejected =
        ProxyIngressStreamPreflightDecision.Rejected(
            failure = failure,
            response = ProxyErrorResponseMapper.map(failure),
            requiresAuditLog = requiresAuditLog,
            headerBytesRead = headerBytesRead,
        )
}
