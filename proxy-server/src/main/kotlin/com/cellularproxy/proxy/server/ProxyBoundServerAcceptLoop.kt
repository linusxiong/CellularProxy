package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.errors.ProxyErrorResponseDecision
import com.cellularproxy.proxy.errors.ProxyErrorResponseMapper
import com.cellularproxy.proxy.errors.ProxyServerFailure
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.metrics.ProxyTrafficMetricsEvent
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ProxyBoundServerAcceptLoopResult {
    data class Stopped(
        val acceptedClientConnections: Long,
    ) : ProxyBoundServerAcceptLoopResult {
        init {
            require(acceptedClientConnections >= 0) {
                "Accepted client connections must be non-negative"
            }
        }
    }

    data class Failed(
        val acceptedClientConnections: Long,
        val failure: Exception,
    ) : ProxyBoundServerAcceptLoopResult {
        init {
            require(acceptedClientConnections >= 0) {
                "Accepted client connections must be non-negative"
            }
        }
    }
}

class ProxyBoundServerAcceptLoop(
    private val connectionHandler: ProxyBoundClientConnectionHandler,
    private val workerExecutor: Executor,
    private val queuedClientTimeoutExecutor: ScheduledExecutorService,
) {
    private val stopRequested = AtomicBoolean(false)

    fun run(
        listener: BoundProxyServerSocket,
        config: ProxyIngressPreflightConfig,
        clientHeaderReadIdleTimeoutMillis: Int = DEFAULT_ACCEPT_LOOP_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS,
        httpBufferSize: Int = DEFAULT_ACCEPT_LOOP_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ACCEPT_LOOP_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_ACCEPT_LOOP_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_ACCEPT_LOOP_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_ACCEPT_LOOP_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyBoundServerAcceptLoopResult =
        run(
            listener = listener,
            configProvider = { config },
            clientHeaderReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis,
            httpBufferSize = httpBufferSize,
            maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
            maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
            maxResponseTrailerBytes = maxResponseTrailerBytes,
            connectRelayBufferSize = connectRelayBufferSize,
            recordMetricEvent = recordMetricEvent,
        )

    fun run(
        listener: BoundProxyServerSocket,
        configProvider: () -> ProxyIngressPreflightConfig,
        clientHeaderReadIdleTimeoutMillis: Int = DEFAULT_ACCEPT_LOOP_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS,
        httpBufferSize: Int = DEFAULT_ACCEPT_LOOP_HTTP_BUFFER_BYTES,
        maxOriginResponseHeaderBytes: Int = DEFAULT_ACCEPT_LOOP_ORIGIN_RESPONSE_HEADER_BYTES,
        maxResponseChunkHeaderBytes: Int = DEFAULT_ACCEPT_LOOP_RESPONSE_CHUNK_HEADER_BYTES,
        maxResponseTrailerBytes: Int = DEFAULT_ACCEPT_LOOP_RESPONSE_TRAILER_BYTES,
        connectRelayBufferSize: Int = DEFAULT_ACCEPT_LOOP_CONNECT_RELAY_BUFFER_BYTES,
        recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit = {},
    ): ProxyBoundServerAcceptLoopResult {
        require(clientHeaderReadIdleTimeoutMillis > 0) {
            "Client header-read idle timeout must be positive"
        }
        require(httpBufferSize > 0) { "HTTP buffer size must be positive" }
        require(maxOriginResponseHeaderBytes > 0) { "Maximum origin response header bytes must be positive" }
        require(maxResponseChunkHeaderBytes > 0) { "Maximum response chunk header bytes must be positive" }
        require(maxResponseTrailerBytes >= 0) { "Maximum response trailer bytes must be non-negative" }
        require(connectRelayBufferSize > 0) { "CONNECT relay buffer size must be positive" }

        var acceptedClientConnections = 0L
        while (!stopRequested.get()) {
            val client = try {
                listener.accept(headerReadIdleTimeoutMillis = clientHeaderReadIdleTimeoutMillis)
            } catch (exception: Exception) {
                if (stopRequested.get() || listener.isClosed) {
                    return ProxyBoundServerAcceptLoopResult.Stopped(acceptedClientConnections)
                }
                return ProxyBoundServerAcceptLoopResult.Failed(acceptedClientConnections, exception)
            }

            acceptedClientConnections += 1
            val reservation = connectionHandler.reserveAccepted(
                client = client,
                recordMetricEvent = recordMetricEvent,
            )
            val queueClaimed = AtomicBoolean(false)
            val queuedTimeout = try {
                queuedClientTimeoutExecutor.schedule(
                    {
                        if (queueClaimed.compareAndSet(false, true)) {
                            writeQueuedIdleTimeout(
                                reservation = reservation,
                                recordMetricEvent = recordMetricEvent,
                            )
                        }
                    },
                    clientHeaderReadIdleTimeoutMillis.toLong(),
                    TimeUnit.MILLISECONDS,
                )
            } catch (exception: RejectedExecutionException) {
                reservation.release()
                client.closeQuietly()
                return ProxyBoundServerAcceptLoopResult.Failed(acceptedClientConnections, exception)
            }
            try {
                workerExecutor.execute {
                    if (queueClaimed.compareAndSet(false, true)) {
                        queuedTimeout.cancel(false)
                        val currentConfig = try {
                            configProvider()
                        } catch (_: Exception) {
                            reservation.release()
                            client.closeQuietly()
                            return@execute
                        }
                        connectionHandler.handleReserved(
                            reservation = reservation,
                            config = currentConfig,
                            httpBufferSize = httpBufferSize,
                            maxOriginResponseHeaderBytes = maxOriginResponseHeaderBytes,
                            maxResponseChunkHeaderBytes = maxResponseChunkHeaderBytes,
                            maxResponseTrailerBytes = maxResponseTrailerBytes,
                            connectRelayBufferSize = connectRelayBufferSize,
                            recordMetricEvent = recordMetricEvent,
                        )
                    }
                }
            } catch (exception: RejectedExecutionException) {
                queuedTimeout.cancel(false)
                reservation.release()
                client.closeQuietly()
                return ProxyBoundServerAcceptLoopResult.Failed(acceptedClientConnections, exception)
            }
        }

        return ProxyBoundServerAcceptLoopResult.Stopped(acceptedClientConnections)
    }

    fun stop(listener: BoundProxyServerSocket) {
        stopRequested.set(true)
        listener.closeQuietly()
    }
}

private fun writeQueuedIdleTimeout(
    reservation: ProxyBoundClientConnectionHandler.AcceptedClientReservation,
    recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit,
) {
    try {
        val bytesWritten = when (val response = ProxyErrorResponseMapper.map(ProxyServerFailure.IdleTimeout)) {
            is ProxyErrorResponseDecision.Emit -> {
                val bytes = response.response.toByteArray()
                reservation.client.output.write(bytes)
                reservation.client.output.flush()
                bytes.size.toLong()
            }
            ProxyErrorResponseDecision.Suppress -> 0L
        }
        ProxyTrafficMetricsEvent.ConnectionRejected.recordSafely(recordMetricEvent)
        if (bytesWritten > 0) {
            ProxyTrafficMetricsEvent.BytesSent(bytesWritten).recordSafely(recordMetricEvent)
        }
    } catch (_: Exception) {
        // Queued clients may disconnect before the timeout response can be written.
    } finally {
        reservation.release()
        reservation.client.closeQuietly()
    }
}

private fun BoundProxyServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Closing is best-effort when stopping the accept loop.
    }
}

private fun ProxyClientStreamConnection.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Preserve the executor rejection as the loop failure.
    }
}

private fun ProxyTrafficMetricsEvent.recordSafely(recordMetricEvent: (ProxyTrafficMetricsEvent) -> Unit) {
    try {
        recordMetricEvent(this)
    } catch (_: Exception) {
        // Metrics sinks are best-effort and must not interrupt proxy handling.
    }
}

private const val DEFAULT_ACCEPT_LOOP_CLIENT_HEADER_READ_IDLE_TIMEOUT_MILLIS = 60_000
private const val DEFAULT_ACCEPT_LOOP_HTTP_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_ACCEPT_LOOP_ORIGIN_RESPONSE_HEADER_BYTES = 16 * 1024
private const val DEFAULT_ACCEPT_LOOP_RESPONSE_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_ACCEPT_LOOP_RESPONSE_TRAILER_BYTES = 16 * 1024
private const val DEFAULT_ACCEPT_LOOP_CONNECT_RELAY_BUFFER_BYTES = 8 * 1024
