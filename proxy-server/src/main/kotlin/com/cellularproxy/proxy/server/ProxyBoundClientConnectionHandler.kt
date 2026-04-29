package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import java.util.concurrent.atomic.AtomicBoolean
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
    internal class AcceptedClientReservation internal constructor(
        val client: ProxyClientStreamConnection,
        val activeConnectionsBeforeAdmission: Long,
        private val releaseAction: () -> Unit,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                releaseAction()
            }
        }
    }

    private val activeConnectionCount = AtomicLong(0)

    val activeClientConnections: Long
        get() = activeConnectionCount.get()

    val activeProxyExchanges: Long
        get() = exchangeHandler.activeProxyExchanges

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
        val reservation =
            reserveAccepted(
                client = client,
                recordMetricEvent = recordMetricEvent,
            )
        return handleReserved(
            reservation = reservation,
            config = config,
            httpBufferSize = httpBufferSize,
            maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
            maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
            maxResponseTrailerBytes = maxResponseTrailerBytes,
            connectRelayBufferSize = connectRelayBufferSize,
            recordMetricEvent = recordMetricEvent,
        )
    }

    fun handleAccepted(
        client: ProxyClientStreamConnection,
        config: ProxyIngressPreflightConfig,
        httpBufferSize: Int = DEFAULT_BOUND_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_BOUND_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_BOUND_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_BOUND_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_BOUND_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyBoundClientConnectionHandlingResult {
        val reservation =
            reserveAccepted(
                client = client,
                recordMetricEvent = recordMetricEvent,
            )
        return handleReserved(
            reservation = reservation,
            config = config,
            httpBufferSize = httpBufferSize,
            maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
            maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
            maxResponseTrailerBytes = maxResponseTrailerBytes,
            connectRelayBufferSize = connectRelayBufferSize,
            recordMetricEvent = recordMetricEvent,
        )
    }

    internal fun reserveAccepted(
        client: ProxyClientStreamConnection,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): AcceptedClientReservation {
        val activeConnectionsBeforeAdmission = activeConnectionCount.getAndIncrement()
        ProxyTrafficMetricsEvent.ConnectionAccepted.recordSafely(recordMetricEvent)
        return AcceptedClientReservation(
            client = client,
            activeConnectionsBeforeAdmission = activeConnectionsBeforeAdmission,
            releaseAction = {
                activeConnectionCount.decrementAndGet()
                ProxyTrafficMetricsEvent.ConnectionClosed.recordSafely(recordMetricEvent)
            },
        )
    }

    internal fun handleReserved(
        reservation: AcceptedClientReservation,
        config: ProxyIngressPreflightConfig,
        httpBufferSize: Int = DEFAULT_BOUND_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_BOUND_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_BOUND_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_BOUND_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_BOUND_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyBoundClientConnectionHandlingResult = try {
        ProxyBoundClientConnectionHandlingResult(
            activeConnectionsBeforeAdmission = reservation.activeConnectionsBeforeAdmission,
            exchange =
                exchangeHandler.handle(
                    config = config,
                    activeConnections = reservation.activeConnectionsBeforeAdmission,
                    client = reservation.client,
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
        reservation.release()
    }
}

private fun ProxyTrafficMetricsEvent.isSocketLifecycleEvent(): Boolean = this == ProxyTrafficMetricsEvent.ConnectionAccepted ||
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
