package com.cellularproxy.proxy.forwarding

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ConnectTunnelClientConnection(
    val input: InputStream,
    val output: OutputStream,
    private val shutdownInputAction: () -> Unit = { input.close() },
    private val shutdownOutputAction: () -> Unit = { output.close() },
) : Closeable {
    fun shutdownInput() {
        shutdownInputAction()
    }

    fun shutdownOutput() {
        shutdownOutputAction()
    }

    override fun close() {
        var failure: Throwable? = null

        try {
            input.close()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        try {
            output.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run {
                failure = throwable
            }
        }

        failure?.let { throw it }
    }
}

sealed interface ConnectTunnelBidirectionalRelayResult {
    val clientToOrigin: ConnectTunnelStreamRelayResult
    val originToClient: ConnectTunnelStreamRelayResult

    val totalBytesRelayed: Long
        get() = clientToOrigin.bytesRelayed + originToClient.bytesRelayed

    data class Completed(
        override val clientToOrigin: ConnectTunnelStreamRelayResult.Completed,
        override val originToClient: ConnectTunnelStreamRelayResult.Completed,
    ) : ConnectTunnelBidirectionalRelayResult

    data class Failed(
        override val clientToOrigin: ConnectTunnelStreamRelayResult,
        override val originToClient: ConnectTunnelStreamRelayResult,
    ) : ConnectTunnelBidirectionalRelayResult {
        init {
            require(
                clientToOrigin is ConnectTunnelStreamRelayResult.Failed ||
                    originToClient is ConnectTunnelStreamRelayResult.Failed,
            ) { "Failed tunnel relay result must contain at least one failed direction" }
        }
    }
}

object ConnectTunnelBidirectionalRelay {
    fun relay(
        client: ConnectTunnelClientConnection,
        origin: OutboundConnectTunnelConnection,
        bufferSize: Int = DEFAULT_TUNNEL_RELAY_BUFFER_BYTES,
    ): ConnectTunnelBidirectionalRelayResult {
        require(bufferSize > 0) { "Buffer size must be positive" }

        val executor = Executors.newFixedThreadPool(CONNECT_TUNNEL_RELAY_DIRECTIONS)
        val completionService = ExecutorCompletionService<ConnectTunnelStreamRelayResult>(executor)

        completionService.submit(
            relayTask(ConnectTunnelRelayDirection.ClientToOrigin) {
                ConnectTunnelStreamRelay.relayOneWay(
                    input = client.input,
                    output = origin.output,
                    direction = ConnectTunnelRelayDirection.ClientToOrigin,
                    bufferSize = bufferSize,
                )
            },
        )
        completionService.submit(
            relayTask(ConnectTunnelRelayDirection.OriginToClient) {
                ConnectTunnelStreamRelay.relayOneWay(
                    input = origin.input,
                    output = client.output,
                    direction = ConnectTunnelRelayDirection.OriginToClient,
                    bufferSize = bufferSize,
                )
            },
        )

        return try {
            var tunnelClosedByCoordinator = false
            val first = completionService.take().getRelayResult()
            if (first is ConnectTunnelStreamRelayResult.Failed) {
                closeQuietly(client)
                closeQuietly(origin)
                tunnelClosedByCoordinator = true
            } else {
                shutdownCompletedDirection(
                    direction = first.direction,
                    client = client,
                    origin = origin,
                )
            }

            val second =
                completionService
                    .take()
                    .getRelayResult()
                    .normalizeIfCoordinatorClosed(tunnelClosedByCoordinator)
            if (second is ConnectTunnelStreamRelayResult.Failed) {
                closeQuietly(client)
                closeQuietly(origin)
            } else {
                shutdownCompletedDirection(
                    direction = second.direction,
                    client = client,
                    origin = origin,
                )
            }
            buildResult(first, second)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            closeQuietly(client)
            closeQuietly(origin)
            executor.shutdownNow()
        }
    }

    private fun relayTask(
        direction: ConnectTunnelRelayDirection,
        block: () -> ConnectTunnelStreamRelayResult,
    ): Callable<ConnectTunnelStreamRelayResult> =
        Callable {
            try {
                block()
            } catch (_: Exception) {
                ConnectTunnelStreamRelayResult.Failed(
                    direction = direction,
                    bytesRelayed = 0,
                    reason = ConnectTunnelStreamRelayFailure.UnexpectedRelayFailure,
                )
            }
        }

    private fun buildResult(
        first: ConnectTunnelStreamRelayResult,
        second: ConnectTunnelStreamRelayResult,
    ): ConnectTunnelBidirectionalRelayResult {
        val clientToOrigin =
            resultForDirection(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                first = first,
                second = second,
            )
        val originToClient =
            resultForDirection(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                first = first,
                second = second,
            )

        return if (
            clientToOrigin is ConnectTunnelStreamRelayResult.Completed &&
            originToClient is ConnectTunnelStreamRelayResult.Completed
        ) {
            ConnectTunnelBidirectionalRelayResult.Completed(
                clientToOrigin = clientToOrigin,
                originToClient = originToClient,
            )
        } else {
            ConnectTunnelBidirectionalRelayResult.Failed(
                clientToOrigin = clientToOrigin,
                originToClient = originToClient,
            )
        }
    }

    private fun resultForDirection(
        direction: ConnectTunnelRelayDirection,
        first: ConnectTunnelStreamRelayResult,
        second: ConnectTunnelStreamRelayResult,
    ): ConnectTunnelStreamRelayResult =
        when {
            first.direction == direction -> first
            second.direction == direction -> second
            else -> error("Missing relay result for $direction")
        }

    private fun Future<ConnectTunnelStreamRelayResult>.getRelayResult(): ConnectTunnelStreamRelayResult =
        try {
            get()
        } catch (execution: ExecutionException) {
            throw execution.cause ?: execution
        }

    private fun ConnectTunnelStreamRelayResult.normalizeIfCoordinatorClosed(
        tunnelClosedByCoordinator: Boolean,
    ): ConnectTunnelStreamRelayResult =
        if (tunnelClosedByCoordinator && this is ConnectTunnelStreamRelayResult.Failed) {
            ConnectTunnelStreamRelayResult.Failed(
                direction = direction,
                bytesRelayed = bytesRelayed,
                reason = ConnectTunnelStreamRelayFailure.StoppedAfterPeerFailure,
            )
        } else {
            this
        }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // Relay results are more useful to callers than ordinary cleanup failures.
        }
    }

    private fun shutdownCompletedDirection(
        direction: ConnectTunnelRelayDirection,
        client: ConnectTunnelClientConnection,
        origin: OutboundConnectTunnelConnection,
    ) {
        when (direction) {
            ConnectTunnelRelayDirection.ClientToOrigin -> {
                shutdownInputQuietly(client)
                shutdownOutputQuietly(origin)
            }
            ConnectTunnelRelayDirection.OriginToClient -> {
                shutdownInputQuietly(origin)
                shutdownOutputQuietly(client)
            }
        }
    }

    private fun shutdownInputQuietly(connection: ConnectTunnelClientConnection) {
        try {
            connection.shutdownInput()
        } catch (_: Exception) {
            // Relay results are more useful to callers than ordinary cleanup failures.
        }
    }

    private fun shutdownInputQuietly(connection: OutboundConnectTunnelConnection) {
        try {
            connection.shutdownInput()
        } catch (_: Exception) {
            // Relay results are more useful to callers than ordinary cleanup failures.
        }
    }

    private fun shutdownOutputQuietly(connection: ConnectTunnelClientConnection) {
        try {
            connection.shutdownOutput()
        } catch (_: Exception) {
            // Relay results are more useful to callers than ordinary cleanup failures.
        }
    }

    private fun shutdownOutputQuietly(connection: OutboundConnectTunnelConnection) {
        try {
            connection.shutdownOutput()
        } catch (_: Exception) {
            // Relay results are more useful to callers than ordinary cleanup failures.
        }
    }
}

private const val CONNECT_TUNNEL_RELAY_DIRECTIONS = 2
