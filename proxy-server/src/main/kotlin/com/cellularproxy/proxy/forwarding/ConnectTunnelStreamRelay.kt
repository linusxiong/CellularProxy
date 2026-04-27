package com.cellularproxy.proxy.forwarding

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class ConnectTunnelRelayDirection {
    ClientToOrigin,
    OriginToClient,
}

enum class ConnectTunnelStreamRelayFailure {
    SourceReadFailed,
    DestinationWriteFailed,
    DestinationFlushFailed,
    UnexpectedRelayFailure,
    StoppedAfterPeerFailure,
}

sealed interface ConnectTunnelStreamRelayResult {
    val direction: ConnectTunnelRelayDirection
    val bytesRelayed: Long

    data class Completed(
        override val direction: ConnectTunnelRelayDirection,
        override val bytesRelayed: Long,
    ) : ConnectTunnelStreamRelayResult {
        init {
            require(bytesRelayed >= 0) { "Relayed byte count must be non-negative" }
        }
    }

    data class Failed(
        override val direction: ConnectTunnelRelayDirection,
        override val bytesRelayed: Long,
        val reason: ConnectTunnelStreamRelayFailure,
    ) : ConnectTunnelStreamRelayResult {
        init {
            require(bytesRelayed >= 0) { "Relayed byte count must be non-negative" }
        }
    }
}

object ConnectTunnelStreamRelay {
    fun relayOneWay(
        input: InputStream,
        output: OutputStream,
        direction: ConnectTunnelRelayDirection,
        bufferSize: Int = DEFAULT_TUNNEL_RELAY_BUFFER_BYTES,
    ): ConnectTunnelStreamRelayResult {
        require(bufferSize > 0) { "Buffer size must be positive" }

        val buffer = ByteArray(bufferSize)
        var bytesRelayed = 0L

        while (true) {
            val readBytes =
                try {
                    input.read(buffer)
                } catch (_: IOException) {
                    return ConnectTunnelStreamRelayResult.Failed(
                        direction = direction,
                        bytesRelayed = bytesRelayed,
                        reason = ConnectTunnelStreamRelayFailure.SourceReadFailed,
                    )
                }

            if (readBytes == END_OF_STREAM) {
                return ConnectTunnelStreamRelayResult.Completed(
                    direction = direction,
                    bytesRelayed = bytesRelayed,
                )
            }

            try {
                output.write(buffer, 0, readBytes)
            } catch (_: IOException) {
                return ConnectTunnelStreamRelayResult.Failed(
                    direction = direction,
                    bytesRelayed = bytesRelayed,
                    reason = ConnectTunnelStreamRelayFailure.DestinationWriteFailed,
                )
            }
            bytesRelayed += readBytes.toLong()

            try {
                output.flush()
            } catch (_: IOException) {
                return ConnectTunnelStreamRelayResult.Failed(
                    direction = direction,
                    bytesRelayed = bytesRelayed,
                    reason = ConnectTunnelStreamRelayFailure.DestinationFlushFailed,
                )
            }
        }
    }
}

internal const val DEFAULT_TUNNEL_RELAY_BUFFER_BYTES = 8 * 1024
private const val END_OF_STREAM = -1
