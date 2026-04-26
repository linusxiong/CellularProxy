package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import java.util.concurrent.atomic.AtomicLong

data class ProxyBoundClientConnectionHandlingResult(
    val activeConnectionsBeforeAdmission: Long,
    val exchange: ProxyClientStreamExchangeHandlingResult,
) {
    init {
        require(activeConnectionsBeforeAdmission >= 0) {
            "Active connections before admission must be non-negative"
        }
    }
}

class ProxyBoundClientConnectionHandler(
    private val exchangeHandler: ProxyClientStreamExchangeHandler,
) {
    private val activeConnectionCount = AtomicLong(0)

    val activeClientConnections: Long
        get() = activeConnectionCount.get()

    fun handleNext(
        listener: BoundProxyServerSocket,
        config: ProxyIngressPreflightConfig,
        clientHeaderReadIdleTimeoutMillis: Int = DEFAULT_BOUND_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS,
        httpBufferSize: Int = DEFAULT_BOUND_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_BOUND_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_BOUND_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_BOUND_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_BOUND_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyBoundClientConnectionHandlingResult {
        require(clientHeaderReadIdleTimeoutMillis > 0) {
            "Client header-read idle timeout must be positive"
        }
        val client = listener.accept(headerReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis)
        val activeConnectionsBeforeAdmission = activeConnectionCount.getAndIncrement()
        ProxyTrafficMetricsEvent.ConnectionAccepted.recordSafely(recordMetricEvent)
        return try {
            ProxyBoundClientConnectionHandlingResult(
                activeConnectionsBeforeAdmission = activeConnectionsBeforeAdmission,
                exchange = exchangeHandler.handle(
                    config = config,
                    activeConnections = activeConnectionsBeforeAdmission,
                    client = client,
                    httpBufferSize = httpBufferSize,
                    maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                    maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                    maxResponseTrailerBytes = maxResponseTrailerBytes,
                    connectRelayBufferSize = connectRelayBufferSize,
                    recordMetricEvent = { event ->
                        if (!event.isSocketLifecycleEvent()) {
                            recordMetricEvent(event)
                        }
                    },
                ),
            )
        } finally {
            activeConnectionCount.decrementAndGet()
            ProxyTrafficMetricsEvent.ConnectionClosed.recordSafely(recordMetricEvent)
        }
    }
}

private fun ProxyTrafficMetricsEvent.isSocketLifecycleEvent(): Boolean =
    this == ProxyTrafficMetricsEvent.ConnectionAccepted ||
        this == ProxyTrafficMetricsEvent.ConnectionClosed

private fun ProxyTrafficMetricsEvent.recordSafely(recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit) {
    try {
        recordMetricEvent(this)
    } catch (_: Exception) {
        // Metrics sinks are best-effort and must not interrupt proxy exchange handling.
    }
}

private const val DEFAULT_BOUND_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS = 60_000
private const val DEFAULT_BOUND_HTTP_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_BOUND_ORIGIN_RESPONSE_HEADER_BYTES = 16 * 1024
private const val DEFAULT_BOUND_RESPONSE_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_BOUND_RESPONSE_TRAILER_BYTES = 16 * 1024
private const val DEFAULT_BOUND_CONNECT_RELAY_BUFFER_BYTES = 8 * 1024
