package com.cellularproxy.proxy.forwarding

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConnectTunnelStreamRelayTest {
    @Test
    fun `relays client bytes to origin until client EOF without closing streams`() {
        val clientInput = CloseTrackingInputStream("hello tunnel".toByteArray(Charsets.UTF_8))
        val originOutput = FlushTrackingOutputStream()

        val result =
            ConnectTunnelStreamRelay.relayOneWay(
                input = clientInput,
                output = originOutput,
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bufferSize = 4,
            )

        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 12,
            ),
            result,
        )
        assertEquals("hello tunnel", originOutput.toString(Charsets.UTF_8))
        assertEquals(1, originOutput.flushCalls)
        assertFalse(clientInput.wasClosed)
        assertFalse(originOutput.wasClosed)
    }

    @Test
    fun `relays binary origin bytes to client without string conversion`() {
        val originBytes = byteArrayOf(0, 1, 2, 127, (-1).toByte())
        val clientOutput = FlushTrackingOutputStream()

        val result =
            ConnectTunnelStreamRelay.relayOneWay(
                input = ByteArrayInputStream(originBytes),
                output = clientOutput,
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bufferSize = 2,
            )

        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = originBytes.size.toLong(),
            ),
            result,
        )
        assertContentEquals(originBytes, clientOutput.toByteArray())
    }

    @Test
    fun `reports source read failure with bytes relayed before failure`() {
        val input = ThrowingReadInputStream(bytesBeforeFailure = "abc".toByteArray(Charsets.UTF_8))
        val output = BufferedUntilFlushOutputStream()

        val result =
            ConnectTunnelStreamRelay.relayOneWay(
                input = input,
                output = output,
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bufferSize = 8,
            )

        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 3,
                reason = ConnectTunnelStreamRelayFailure.SourceReadFailed,
            ),
            result,
        )
        assertEquals("abc", output.visibleString())
        assertEquals(1, output.flushCalls)
    }

    @Test
    fun `reports destination write failure with only fully written bytes counted`() {
        val input = ByteArrayInputStream("abcdef".toByteArray(Charsets.UTF_8))
        val output = ThrowingSecondWriteOutputStream()

        val result =
            ConnectTunnelStreamRelay.relayOneWay(
                input = input,
                output = output,
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bufferSize = 3,
            )

        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = 3,
                reason = ConnectTunnelStreamRelayFailure.DestinationWriteFailed,
            ),
            result,
        )
        assertEquals("abc", output.toString(Charsets.UTF_8))
        assertEquals(1, output.flushCalls)
    }

    @Test
    fun `reports destination flush failure after all bytes are relayed`() {
        val input = ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))
        val output = ThrowingFlushOutputStream()

        val result =
            ConnectTunnelStreamRelay.relayOneWay(
                input = input,
                output = output,
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
            )

        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 3,
                reason = ConnectTunnelStreamRelayFailure.DestinationFlushFailed,
            ),
            result,
        )
        assertEquals("abc", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects impossible relay result and argument values`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelStreamRelay.relayOneWay(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bufferSize = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = -1,
                reason = ConnectTunnelStreamRelayFailure.SourceReadFailed,
            )
        }
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private open class FlushTrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed: Boolean = false
            private set

        var flushCalls: Int = 0
            private set

        override fun flush() {
            flushCalls += 1
            super.flush()
        }

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private class ThrowingReadInputStream(
        private val bytesBeforeFailure: ByteArray,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index < bytesBeforeFailure.size) {
                return bytesBeforeFailure[index++].toInt() and 0xff
            }
            throw IOException("read failed")
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (index < bytesBeforeFailure.size) {
                val count = minOf(length, bytesBeforeFailure.size - index)
                bytesBeforeFailure.copyInto(buffer, destinationOffset = offset, startIndex = index, endIndex = index + count)
                index += count
                return count
            }
            throw IOException("read failed")
        }
    }

    private class BufferedUntilFlushOutputStream : OutputStream() {
        private val pending = ByteArrayOutputStream()
        private val visible = ByteArrayOutputStream()

        var flushCalls: Int = 0
            private set

        override fun write(value: Int) {
            pending.write(value)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            pending.write(buffer, offset, length)
        }

        override fun flush() {
            flushCalls += 1
            pending.writeTo(visible)
            pending.reset()
        }

        fun visibleString(): String = visible.toString(Charsets.UTF_8)
    }

    private class ThrowingSecondWriteOutputStream : FlushTrackingOutputStream() {
        private var writeCalls = 0

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeCalls += 1
            if (writeCalls > 1) {
                throw IOException("write failed")
            }
            super.write(buffer, offset, length)
        }
    }

    private class ThrowingFlushOutputStream : FlushTrackingOutputStream() {
        override fun flush(): Unit = throw IOException("flush failed")
    }
}
