package com.cellularproxy.proxy.forwarding

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectTunnelBidirectionalRelayTest {
    @Test
    fun `relays both tunnel directions and closes owned streams after both finish`() {
        val clientInput = CloseTrackingInputStream("client request".toByteArray(Charsets.UTF_8))
        val clientOutput = CloseTrackingOutputStream()
        val originInput = CloseTrackingInputStream("origin response".toByteArray(Charsets.UTF_8))
        val originOutput = CloseTrackingOutputStream()

        val result = ConnectTunnelBidirectionalRelay.relay(
            client = ConnectTunnelClientConnection(
                input = clientInput,
                output = clientOutput,
            ),
            origin = OutboundConnectTunnelConnection(
                input = originInput,
                output = originOutput,
                host = "origin.example",
                port = 443,
            ),
            bufferSize = 4,
        )

        val completed = assertIs<ConnectTunnelBidirectionalRelayResult.Completed>(result)
        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 14,
            ),
            completed.clientToOrigin,
        )
        assertEquals(
            ConnectTunnelStreamRelayResult.Completed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = 15,
            ),
            completed.originToClient,
        )
        assertEquals(29, completed.totalBytesRelayed)
        assertEquals("client request", originOutput.toString(Charsets.UTF_8))
        assertEquals("origin response", clientOutput.toString(Charsets.UTF_8))
        assertTrue(clientInput.wasClosed)
        assertTrue(clientOutput.wasClosed)
        assertTrue(originInput.wasClosed)
        assertTrue(originOutput.wasClosed)
    }

    @Test
    fun `clean EOF in one direction does not truncate delayed peer bytes`() {
        val originInputBlocked = CountDownLatch(1)
        val releaseOriginInput = CountDownLatch(1)
        val clientInput = CloseTrackingInputStream("client".toByteArray(Charsets.UTF_8))
        val clientOutput = CloseTrackingOutputStream()
        val originInput = DelayedInputStream(
            bytes = "origin".toByteArray(Charsets.UTF_8),
            blocked = originInputBlocked,
            release = releaseOriginInput,
        )
        val originOutput = CloseTrackingOutputStream()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future = executor.submit<ConnectTunnelBidirectionalRelayResult> {
                ConnectTunnelBidirectionalRelay.relay(
                    client = ConnectTunnelClientConnection(
                        input = clientInput,
                        output = clientOutput,
                    ),
                    origin = OutboundConnectTunnelConnection(
                        input = originInput,
                        output = originOutput,
                        host = "origin.example",
                        port = 443,
                    ),
                    bufferSize = 6,
                )
            }

            assertTrue(originOutput.awaitSize(expectedSize = 6, timeoutMillis = 1_000))
            assertEquals("client", originOutput.toString(Charsets.UTF_8))
            assertTrue(originInputBlocked.await(1, TimeUnit.SECONDS))
            assertFalse(originInput.wasClosed)
            assertFalse(clientOutput.wasClosed)

            releaseOriginInput.countDown()

            val result = assertIs<ConnectTunnelBidirectionalRelayResult.Completed>(
                future.get(1, TimeUnit.SECONDS),
            )
            assertEquals(12, result.totalBytesRelayed)
            assertEquals("origin", clientOutput.toString(Charsets.UTF_8))
            assertTrue(clientInput.wasClosed)
            assertTrue(clientOutput.wasClosed)
            assertTrue(originInput.wasClosed)
            assertTrue(originOutput.wasClosed)
        } finally {
            releaseOriginInput.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `returns failed result when either relay direction fails and still closes owned streams`() {
        val clientInput = BlockingAfterFirstChunkInputStream(
            firstChunk = "client".toByteArray(Charsets.UTF_8),
            blocked = CountDownLatch(1),
            release = CountDownLatch(1),
            failWhenClosed = true,
        )
        val clientOutput = CloseTrackingOutputStream()
        val originInput = ThrowingReadInputStream("abc".toByteArray(Charsets.UTF_8))
        val originOutput = CloseTrackingOutputStream()

        val result = ConnectTunnelBidirectionalRelay.relay(
            client = ConnectTunnelClientConnection(
                input = clientInput,
                output = clientOutput,
            ),
            origin = OutboundConnectTunnelConnection(
                input = originInput,
                output = originOutput,
                host = "origin.example",
                port = 443,
            ),
            bufferSize = 8,
        )

        val failed = assertIs<ConnectTunnelBidirectionalRelayResult.Failed>(result)
        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 6,
                reason = ConnectTunnelStreamRelayFailure.StoppedAfterPeerFailure,
            ),
            failed.clientToOrigin,
        )
        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.OriginToClient,
                bytesRelayed = 3,
                reason = ConnectTunnelStreamRelayFailure.SourceReadFailed,
            ),
            failed.originToClient,
        )
        assertEquals(9, failed.totalBytesRelayed)
        assertEquals("client", originOutput.toString(Charsets.UTF_8))
        assertEquals("abc", clientOutput.toString(Charsets.UTF_8))
        assertTrue(clientInput.wasClosed)
        assertTrue(clientOutput.wasClosed)
        assertTrue(originInput.wasClosed)
        assertTrue(originOutput.wasClosed)
    }

    @Test
    fun `uses connection shutdown callbacks instead of full stream close after clean directional EOF`() {
        var clientInputShutdowns = 0
        var originOutputShutdowns = 0
        val clientInput = CloseTrackingInputStream("client".toByteArray(Charsets.UTF_8))
        val clientOutput = CloseTrackingOutputStream()
        val originInput = CloseTrackingInputStream("origin".toByteArray(Charsets.UTF_8))
        val originOutput = CloseTrackingOutputStream()

        val result = ConnectTunnelBidirectionalRelay.relay(
            client = ConnectTunnelClientConnection(
                input = clientInput,
                output = clientOutput,
                shutdownInputAction = { clientInputShutdowns += 1 },
            ),
            origin = OutboundConnectTunnelConnection(
                input = originInput,
                output = originOutput,
                host = "origin.example",
                port = 443,
                shutdownOutputAction = { originOutputShutdowns += 1 },
            ),
            bufferSize = 8,
        )

        assertIs<ConnectTunnelBidirectionalRelayResult.Completed>(result)
        assertEquals(1, clientInputShutdowns)
        assertEquals(1, originOutputShutdowns)
        assertTrue(clientInput.wasClosed)
        assertTrue(originOutput.wasClosed)
    }

    @Test
    fun `maps unexpected relay task exceptions to sanitized failure result`() {
        val result = ConnectTunnelBidirectionalRelay.relay(
            client = ConnectTunnelClientConnection(
                input = ThrowingRuntimeInputStream(),
                output = CloseTrackingOutputStream(),
            ),
            origin = OutboundConnectTunnelConnection(
                input = CloseTrackingInputStream(ByteArray(0)),
                output = CloseTrackingOutputStream(),
                host = "origin.example",
                port = 443,
            ),
            bufferSize = 8,
        )

        val failed = assertIs<ConnectTunnelBidirectionalRelayResult.Failed>(result)
        assertEquals(
            ConnectTunnelStreamRelayResult.Failed(
                direction = ConnectTunnelRelayDirection.ClientToOrigin,
                bytesRelayed = 0,
                reason = ConnectTunnelStreamRelayFailure.UnexpectedRelayFailure,
            ),
            failed.clientToOrigin,
        )
    }

    @Test
    fun `does not swallow fatal relay task errors`() {
        assertFailsWith<OutOfMemoryError> {
            ConnectTunnelBidirectionalRelay.relay(
                client = ConnectTunnelClientConnection(
                    input = ThrowingFatalInputStream(),
                    output = CloseTrackingOutputStream(),
                ),
                origin = OutboundConnectTunnelConnection(
                    input = CloseTrackingInputStream(ByteArray(0)),
                    output = CloseTrackingOutputStream(),
                    host = "origin.example",
                    port = 443,
                ),
                bufferSize = 8,
            )
        }
    }

    @Test
    fun `rejects impossible relay arguments and failed results`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelBidirectionalRelay.relay(
                client = ConnectTunnelClientConnection(
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = ByteArrayOutputStream(),
                ),
                origin = OutboundConnectTunnelConnection(
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = ByteArrayOutputStream(),
                    host = "origin.example",
                    port = 443,
                ),
                bufferSize = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ConnectTunnelBidirectionalRelayResult.Failed(
                clientToOrigin = ConnectTunnelStreamRelayResult.Completed(
                    direction = ConnectTunnelRelayDirection.ClientToOrigin,
                    bytesRelayed = 1,
                ),
                originToClient = ConnectTunnelStreamRelayResult.Completed(
                    direction = ConnectTunnelRelayDirection.OriginToClient,
                    bytesRelayed = 1,
                ),
            )
        }
    }

    private open class CloseTrackingInputStream(
        private val delegate: ByteArrayInputStream,
    ) : InputStream() {
        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))

        var wasClosed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            wasClosed = true
            delegate.close()
        }
    }

    private open class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed: Boolean = false
            private set

        fun awaitSize(expectedSize: Int, timeoutMillis: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (System.nanoTime() < deadline) {
                if (size() >= expectedSize) {
                    return true
                }
                Thread.sleep(10)
            }
            return size() >= expectedSize
        }

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private class BlockingAfterFirstChunkInputStream(
        private val firstChunk: ByteArray,
        private val blocked: CountDownLatch,
        private val release: CountDownLatch,
        private val failWhenClosed: Boolean = false,
    ) : InputStream() {
        private var index = 0
        private var released = false
        var wasClosed: Boolean = false
            private set

        override fun read(): Int {
            val buffer = ByteArray(1)
            val count = read(buffer, 0, 1)
            return if (count == -1) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index < firstChunk.size) {
                val count = minOf(length, firstChunk.size - index)
                firstChunk.copyInto(buffer, destinationOffset = offset, startIndex = index, endIndex = index + count)
                index += count
                return count
            }

            if (!released) {
                blocked.countDown()
                while (!wasClosed && !release.await(10, TimeUnit.MILLISECONDS)) {
                    // Keep checking for close so relay cleanup can unblock this test stream.
                }
                released = true
            }

            if (wasClosed && failWhenClosed) {
                throw IOException("socket closed")
            }

            return -1
        }

        override fun close() {
            wasClosed = true
            release.countDown()
        }
    }

    private class DelayedInputStream(
        private val bytes: ByteArray,
        private val blocked: CountDownLatch,
        private val release: CountDownLatch,
    ) : InputStream() {
        private var index = 0
        private var released = false
        var wasClosed: Boolean = false
            private set

        override fun read(): Int {
            val buffer = ByteArray(1)
            val count = read(buffer, 0, 1)
            return if (count == -1) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!released) {
                blocked.countDown()
                while (!wasClosed && !release.await(10, TimeUnit.MILLISECONDS)) {
                    // Keep checking for close so the test can fail deterministically on truncation.
                }
                released = true
            }

            if (wasClosed && index < bytes.size) {
                throw IOException("socket closed")
            }

            if (index < bytes.size) {
                val count = minOf(length, bytes.size - index)
                bytes.copyInto(buffer, destinationOffset = offset, startIndex = index, endIndex = index + count)
                index += count
                return count
            }

            return -1
        }

        override fun close() {
            wasClosed = true
            release.countDown()
        }
    }

    private class ThrowingReadInputStream(
        private val bytesBeforeFailure: ByteArray,
    ) : CloseTrackingInputStream(bytesBeforeFailure) {
        private var index = 0

        override fun read(): Int {
            if (index < bytesBeforeFailure.size) {
                return bytesBeforeFailure[index++].toInt() and 0xff
            }
            throw IOException("read failed")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index < bytesBeforeFailure.size) {
                val count = minOf(length, bytesBeforeFailure.size - index)
                bytesBeforeFailure.copyInto(buffer, destinationOffset = offset, startIndex = index, endIndex = index + count)
                index += count
                return count
            }
            throw IOException("read failed")
        }
    }

    private class ThrowingRuntimeInputStream : InputStream() {
        override fun read(): Int {
            throw IllegalStateException("sensitive implementation detail")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw IllegalStateException("sensitive implementation detail")
        }
    }

    private class ThrowingFatalInputStream : InputStream() {
        override fun read(): Int {
            throw OutOfMemoryError("fatal")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throw OutOfMemoryError("fatal")
        }
    }
}
