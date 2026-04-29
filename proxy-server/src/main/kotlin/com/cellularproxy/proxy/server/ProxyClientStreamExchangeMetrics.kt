package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.forwarding.ConnectTunnelBidirectionalRelayResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelRelayDirection
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.HttpProxyRequestStreamForwardingResult
import com.cellularproxy.proxy.forwarding.HttpProxyResponseStreamForwardingResult
import com.cellularproxy.proxy.forwarding.HttpProxyStreamExchangeForwardingResult
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsReducer
import com.cellularproxy.proxy.protocol.HttpBodyStreamCopyResult
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics

object ProxyClientStreamExchangeMetrics {
    fun apply(
        metrics: ProxyTrafficMetrics,
        result: ProxyClientStreamExchangeHandlingResult,
    ): ProxyTrafficMetrics = eventsFor(result).fold(metrics, ProxyTrafficMetricsReducer::apply)

    fun eventsFor(result: ProxyClientStreamExchangeHandlingResult): List<ProxyTrafficMetricsEvent> = when (result) {
        is ProxyClientStreamExchangeHandlingResult.PreflightRejected ->
            rejectedConnectionEvents(
                bytesReceived = result.headerBytesRead.toLong(),
                bytesSent = result.responseBytesWritten.toLong(),
            )
        is ProxyClientStreamExchangeHandlingResult.HttpProxyHandled,
        is ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled,
        is ProxyClientStreamExchangeHandlingResult.TransparentTlsTunnelHandled,
        is ProxyClientStreamExchangeHandlingResult.ManagementHandled,
        ->
            acceptedStartedEvents(result) + acceptedCompletedEvents(result)
    }

    fun acceptedStartedEvents(result: ProxyClientStreamExchangeHandlingResult): List<ProxyTrafficMetricsEvent> = when (result) {
        is ProxyClientStreamExchangeHandlingResult.HttpProxyHandled ->
            acceptedStartedEvents(result.headerBytesRead)
        is ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled ->
            acceptedStartedEvents(result.headerBytesRead)
        is ProxyClientStreamExchangeHandlingResult.TransparentTlsTunnelHandled ->
            acceptedStartedEvents(result.initialBytesRead)
        is ProxyClientStreamExchangeHandlingResult.ManagementHandled ->
            acceptedStartedEvents(result.headerBytesRead)
        is ProxyClientStreamExchangeHandlingResult.PreflightRejected -> emptyList()
    }

    fun acceptedStartedEvents(headerBytesRead: Int): List<ProxyTrafficMetricsEvent> {
        require(headerBytesRead >= 0) { "Header bytes read must be non-negative" }
        return acceptedStartEvents(headerBytesRead.toLong())
    }

    fun acceptedCompletedEvents(result: ProxyClientStreamExchangeHandlingResult): List<ProxyTrafficMetricsEvent> = when (result) {
        is ProxyClientStreamExchangeHandlingResult.HttpProxyHandled ->
            acceptedCompletionEvents(
                bytesReceived = result.result.clientBytesReceived(),
                bytesSent = result.result.clientBytesSent(),
            )
        is ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled ->
            acceptedCompletionEvents(
                bytesReceived = result.result.clientBytesReceived(),
                bytesSent = result.result.clientBytesSent(),
            )
        is ProxyClientStreamExchangeHandlingResult.TransparentTlsTunnelHandled ->
            acceptedCompletionEvents(
                bytesReceived = result.result.clientBytesReceived(),
                bytesSent = result.result.clientBytesSent(),
            )
        is ProxyClientStreamExchangeHandlingResult.ManagementHandled ->
            acceptedCompletionEvents(
                bytesReceived = 0,
                bytesSent = result.result.clientBytesSent(),
            )
        is ProxyClientStreamExchangeHandlingResult.PreflightRejected -> emptyList()
    }

    fun acceptedClosedEvent(): ProxyTrafficMetricsEvent = ProxyTrafficMetricsEvent.ConnectionClosed

    private fun rejectedConnectionEvents(
        bytesReceived: Long,
        bytesSent: Long,
    ): List<ProxyTrafficMetricsEvent> = buildList {
        add(ProxyTrafficMetricsEvent.ConnectionRejected)
        addByteEvents(bytesReceived = bytesReceived, bytesSent = bytesSent)
    }

    private fun acceptedStartEvents(bytesReceived: Long): List<ProxyTrafficMetricsEvent> = buildList {
        add(ProxyTrafficMetricsEvent.ConnectionAccepted)
        addByteEvents(bytesReceived = bytesReceived, bytesSent = 0)
    }

    private fun acceptedCompletionEvents(
        bytesReceived: Long,
        bytesSent: Long,
    ): List<ProxyTrafficMetricsEvent> = buildList {
        addByteEvents(bytesReceived = bytesReceived, bytesSent = bytesSent)
        add(acceptedClosedEvent())
    }

    private fun MutableList<ProxyTrafficMetricsEvent>.addByteEvents(
        bytesReceived: Long,
        bytesSent: Long,
    ) {
        if (bytesReceived > 0) {
            add(ProxyTrafficMetricsEvent.BytesReceived(bytesReceived))
        }
        if (bytesSent > 0) {
            add(ProxyTrafficMetricsEvent.BytesSent(bytesSent))
        }
    }
}

private fun HttpProxyOutboundExchangeHandlingResult.clientBytesReceived(): Long = when (this) {
    is HttpProxyOutboundExchangeHandlingResult.Forwarded -> result.clientBytesReceived()
    is HttpProxyOutboundExchangeHandlingResult.ConnectionFailed -> 0
    is HttpProxyOutboundExchangeHandlingResult.UnsupportedAcceptedRequest -> 0
}

private fun HttpProxyOutboundExchangeHandlingResult.clientBytesSent(): Long = when (this) {
    is HttpProxyOutboundExchangeHandlingResult.Forwarded -> result.clientBytesSent()
    is HttpProxyOutboundExchangeHandlingResult.ConnectionFailed -> errorResponseBytesWritten.toLong()
    is HttpProxyOutboundExchangeHandlingResult.UnsupportedAcceptedRequest -> 0
}

private fun HttpProxyStreamExchangeForwardingResult.clientBytesReceived(): Long = when (this) {
    is HttpProxyStreamExchangeForwardingResult.Forwarded -> requestBodyBytesWritten
    is HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed -> result.clientBytesReceived()
    is HttpProxyStreamExchangeForwardingResult.OriginResponsePreflightRejected -> requestBodyBytesWritten
    is HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed -> requestBodyBytesWritten
}

private fun HttpProxyStreamExchangeForwardingResult.clientBytesSent(): Long = when (this) {
    is HttpProxyStreamExchangeForwardingResult.Forwarded ->
        responseHeaderBytesWritten.toLong() + responseBodyBytesWritten
    is HttpProxyStreamExchangeForwardingResult.RequestForwardingFailed -> 0
    is HttpProxyStreamExchangeForwardingResult.OriginResponsePreflightRejected -> 0
    is HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed -> result.clientBytesSent()
}

private fun HttpProxyRequestStreamForwardingResult.clientBytesReceived(): Long = when (this) {
    is HttpProxyRequestStreamForwardingResult.Forwarded -> bodyBytesWritten
    is HttpProxyRequestStreamForwardingResult.BodyCopyFailed -> copyResult.bytesCopied()
    is HttpProxyRequestStreamForwardingResult.Rejected -> 0
}

private fun HttpProxyResponseStreamForwardingResult.clientBytesSent(): Long = when (this) {
    is HttpProxyResponseStreamForwardingResult.Forwarded -> headerBytesWritten.toLong() + bodyBytesWritten
    is HttpProxyResponseStreamForwardingResult.BodyCopyFailed ->
        headerBytesWritten.toLong() + copyResult.bytesCopied()
    is HttpProxyResponseStreamForwardingResult.Rejected -> 0
}

private fun ConnectTunnelOutboundExchangeRelayHandlingResult.clientBytesReceived(): Long = when (this) {
    is ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed ->
        relayResult.clientToOriginBytesRelayed()
    is ConnectTunnelOutboundExchangeRelayHandlingResult.ConnectionFailed -> 0
    is ConnectTunnelOutboundExchangeRelayHandlingResult.UnsupportedAcceptedRequest -> 0
}

private fun ConnectTunnelOutboundExchangeRelayHandlingResult.clientBytesSent(): Long = when (this) {
    is ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed ->
        responseBytesWritten.toLong() + relayResult.originToClientBytesRelayed()
    is ConnectTunnelOutboundExchangeRelayHandlingResult.ConnectionFailed -> errorResponseBytesWritten.toLong()
    is ConnectTunnelOutboundExchangeRelayHandlingResult.UnsupportedAcceptedRequest -> 0
}

private fun ManagementApiStreamExchangeHandlingResult.clientBytesSent(): Long = when (this) {
    is ManagementApiStreamExchangeHandlingResult.Responded -> responseBytesWritten.toLong()
    is ManagementApiStreamExchangeHandlingResult.UnsupportedAcceptedRequest -> 0
}

private fun ConnectTunnelBidirectionalRelayResult.clientToOriginBytesRelayed(): Long = bytesForDirection(ConnectTunnelRelayDirection.ClientToOrigin)

private fun ConnectTunnelBidirectionalRelayResult.originToClientBytesRelayed(): Long = bytesForDirection(ConnectTunnelRelayDirection.OriginToClient)

private fun ConnectTunnelBidirectionalRelayResult.bytesForDirection(direction: ConnectTunnelRelayDirection): Long {
    val result =
        when (direction) {
            ConnectTunnelRelayDirection.ClientToOrigin -> clientToOrigin
            ConnectTunnelRelayDirection.OriginToClient -> originToClient
        }
    return result.bytesRelayed
}

private fun HttpBodyStreamCopyResult.bytesCopied(): Long = when (this) {
    is HttpBodyStreamCopyResult.Completed -> bytesCopied
    is HttpBodyStreamCopyResult.PrematureEnd -> bytesCopied
    is HttpBodyStreamCopyResult.ChunkedPrematureEnd -> bytesCopied
    is HttpBodyStreamCopyResult.MalformedChunk -> bytesCopied
}
