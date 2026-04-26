package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionRejectionReason
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.forwarding.ConnectTunnelBidirectionalRelayResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelOutboundExchangeRelayHandlingResult
import com.cellularproxy.proxy.forwarding.ConnectTunnelRelayDirection
import com.cellularproxy.proxy.forwarding.ConnectTunnelStreamRelayResult
import com.cellularproxy.proxy.forwarding.HttpProxyOutboundExchangeHandlingResult
import com.cellularproxy.proxy.forwarding.HttpProxyResponseStreamForwardingRejectionReason
import com.cellularproxy.proxy.forwarding.HttpProxyResponseStreamForwardingResult
import com.cellularproxy.proxy.forwarding.HttpProxyStreamExchangeForwardingResult
import com.cellularproxy.proxy.forwarding.OriginHttpResponseStreamPreflightRejectionReason
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeHandlingResult
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import com.cellularproxy.proxy.protocol.HttpResponseBodyFramingRejectionReason
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyClientStreamExchangeMetricsTest {
    @Test
    fun `records rejected connection and preflight bytes without opening an active connection`() {
        val result = ProxyClientStreamExchangeHandlingResult.PreflightRejected(
            failure = ProxyServerFailure.ConnectionLimit(
                ConnectionLimitAdmissionRejectionReason.MaximumConcurrentConnectionsReached(
                    activeConnections = 2,
                    maxConcurrentConnections = 2,
                ),
            ),
            responseBytesWritten = 71,
            requiresAuditLog = false,
            headerBytesRead = 19,
        )

        val metrics = ProxyClientStreamExchangeMetrics.apply(
            metrics = ProxyTrafficMetrics(),
            result = result,
        )

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 0,
                totalConnections = 0,
                rejectedConnections = 1,
                bytesReceived = 19,
                bytesSent = 71,
            ),
            metrics,
        )
    }

    @Test
    fun `records accepted HTTP exchange bytes and closes the active connection`() {
        val result = ProxyClientStreamExchangeHandlingResult.HttpProxyHandled(
            headerBytesRead = 83,
            result = HttpProxyOutboundExchangeHandlingResult.Forwarded(
                HttpProxyStreamExchangeForwardingResult.Forwarded(
                    host = "origin.example",
                    port = 80,
                    requestHeaderBytesWritten = 61,
                    requestBodyBytesWritten = 11,
                    responseStatusCode = 200,
                    responseHeaderBytesRead = 42,
                    responseHeaderBytesWritten = 37,
                    responseBodyBytesWritten = 23,
                    mustCloseClientConnection = false,
                ),
            ),
        )

        val metrics = ProxyClientStreamExchangeMetrics.apply(
            metrics = ProxyTrafficMetrics(),
            result = result,
        )

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 0,
                totalConnections = 1,
                rejectedConnections = 0,
                bytesReceived = 94,
                bytesSent = 60,
            ),
            metrics,
        )
    }

    @Test
    fun `preserves HTTP request body bytes when origin response preflight fails`() {
        val result = ProxyClientStreamExchangeHandlingResult.HttpProxyHandled(
            headerBytesRead = 84,
            result = HttpProxyOutboundExchangeHandlingResult.Forwarded(
                HttpProxyStreamExchangeForwardingResult.OriginResponsePreflightRejected(
                    reason = OriginHttpResponseStreamPreflightRejectionReason.IncompleteHeaderBlock,
                    responseHeaderBytesRead = 39,
                    requestBodyBytesWritten = 512,
                ),
            ),
        )

        assertEquals(
            listOf(
                ProxyTrafficMetricsEvent.ConnectionAccepted,
                ProxyTrafficMetricsEvent.BytesReceived(84),
            ),
            ProxyClientStreamExchangeMetrics.acceptedStartedEvents(result),
        )
        assertEquals(
            listOf(
                ProxyTrafficMetricsEvent.BytesReceived(512),
                ProxyTrafficMetricsEvent.ConnectionClosed,
            ),
            ProxyClientStreamExchangeMetrics.acceptedCompletedEvents(result),
        )
    }

    @Test
    fun `preserves HTTP request body bytes and partial response bytes when response forwarding fails`() {
        val result = ProxyClientStreamExchangeHandlingResult.HttpProxyHandled(
            headerBytesRead = 84,
            result = HttpProxyOutboundExchangeHandlingResult.Forwarded(
                HttpProxyStreamExchangeForwardingResult.ResponseForwardingFailed(
                    responseHeaderBytesRead = 40,
                    requestBodyBytesWritten = 512,
                    result = HttpProxyResponseStreamForwardingResult.Rejected(
                        HttpProxyResponseStreamForwardingRejectionReason.BodyFramingRejected(
                            HttpResponseBodyFramingRejectionReason.UnsupportedTransferEncoding,
                        ),
                    ),
                ),
            ),
        )

        val metrics = ProxyClientStreamExchangeMetrics.apply(
            metrics = ProxyTrafficMetrics(),
            result = result,
        )

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 0,
                totalConnections = 1,
                rejectedConnections = 0,
                bytesReceived = 596,
                bytesSent = 0,
            ),
            metrics,
        )
    }

    @Test
    fun `records accepted CONNECT relay bytes and closes the active connection`() {
        val result = ProxyClientStreamExchangeHandlingResult.ConnectTunnelHandled(
            headerBytesRead = 72,
            result = ConnectTunnelOutboundExchangeRelayHandlingResult.Relayed(
                host = "origin.example",
                port = 443,
                responseBytesWritten = 39,
                relayResult = ConnectTunnelBidirectionalRelayResult.Completed(
                    clientToOrigin = ConnectTunnelStreamRelayResult.Completed(
                        direction = ConnectTunnelRelayDirection.ClientToOrigin,
                        bytesRelayed = 101,
                    ),
                    originToClient = ConnectTunnelStreamRelayResult.Completed(
                        direction = ConnectTunnelRelayDirection.OriginToClient,
                        bytesRelayed = 202,
                    ),
                ),
            ),
        )

        val metrics = ProxyClientStreamExchangeMetrics.apply(
            metrics = ProxyTrafficMetrics(),
            result = result,
        )

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 0,
                totalConnections = 1,
                rejectedConnections = 0,
                bytesReceived = 173,
                bytesSent = 241,
            ),
            metrics,
        )
    }

    @Test
    fun `records accepted management response bytes and closes the active connection`() {
        val result = ProxyClientStreamExchangeHandlingResult.ManagementHandled(
            headerBytesRead = 96,
            result = ManagementApiStreamExchangeHandlingResult.Responded(
                statusCode = 202,
                responseBytesWritten = 88,
                requiresAuditLog = true,
                disposition = ManagementApiStreamExchangeDisposition.Routed,
            ),
        )

        val metrics = ProxyClientStreamExchangeMetrics.apply(
            metrics = ProxyTrafficMetrics(),
            result = result,
        )

        assertEquals(
            ProxyTrafficMetrics(
                activeConnections = 0,
                totalConnections = 1,
                rejectedConnections = 0,
                bytesReceived = 96,
                bytesSent = 88,
            ),
            metrics,
        )
    }
}
