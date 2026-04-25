package com.cellularproxy.proxy.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpBodyStreamCopierTest {
    @Test
    fun `copies fixed-length body without consuming following bytes`() {
        val input = ByteArrayInputStream("helloNEXT".toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyFixedLength(
            input = input,
            output = output,
            contentLength = 5,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 5), result)
        assertEquals("hello", output.toString(Charsets.UTF_8))
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `copies fixed-length body across multiple buffer reads`() {
        val inputBytes = ByteArray(10) { index -> index.toByte() }
        val input = ByteArrayInputStream(inputBytes)
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyFixedLength(
            input = input,
            output = output,
            contentLength = inputBytes.size.toLong(),
            bufferSize = 3,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = inputBytes.size.toLong()), result)
        assertContentEquals(inputBytes, output.toByteArray())
    }

    @Test
    fun `copies chunked body including trailers without consuming following bytes`() {
        val chunkedBody = (
            "5\r\n" +
                "hello\r\n" +
                "6;kind=greeting\r\n" +
                " world\r\n" +
                "0\r\n" +
                "Expires: never\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(chunkedBody + "NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyChunked(
            input = input,
            output = output,
            bufferSize = 3,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = chunkedBody.size.toLong()), result)
        assertContentEquals(chunkedBody, output.toByteArray())
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `copies binary chunk data without string conversion`() {
        val chunkedBody = byteArrayOf(
            '3'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            0,
            127,
            (-1).toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            '0'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
        )
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyChunked(
            input = ByteArrayInputStream(chunkedBody),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = chunkedBody.size.toLong()), result)
        assertContentEquals(chunkedBody, output.toByteArray())
    }

    @Test
    fun `copies close-delimited body until end of stream`() {
        val inputBytes = "hello close-delimited body".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyCloseDelimited(
            input = ByteArrayInputStream(inputBytes),
            output = output,
            bufferSize = 5,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = inputBytes.size.toLong()), result)
        assertContentEquals(inputBytes, output.toByteArray())
    }

    @Test
    fun `copies binary close-delimited body without string conversion`() {
        val inputBytes = byteArrayOf(0, 1, 2, 127, (-1).toByte())
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyCloseDelimited(
            input = ByteArrayInputStream(inputBytes),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = inputBytes.size.toLong()), result)
        assertContentEquals(inputBytes, output.toByteArray())
    }

    @Test
    fun `returns premature end for incomplete chunked body`() {
        val input = ByteArrayInputStream("5\r\nabc".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyChunked(
            input = input,
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.ChunkedPrematureEnd(bytesCopied = 6), result)
        assertEquals("5\r\nabc", output.toString(Charsets.US_ASCII))
    }

    @Test
    fun `rejects malformed chunked framing before forwarding invalid chunk lines`() {
        val input = ByteArrayInputStream("Z\r\nNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyChunked(
            input = input,
            output = output,
        )

        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 0,
                reason = HttpChunkedBodyMalformedReason.InvalidChunkSize,
            ),
            result,
        )
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `validates chunk extensions before forwarding chunk lines`() {
        val validChunkedBody = "5 ; name = value ; quoted = \"hello world\"\r\nhello\r\n0\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val validOutput = ByteArrayOutputStream()

        val validResult = HttpBodyStreamCopier.copyChunked(
            input = ByteArrayInputStream(validChunkedBody),
            output = validOutput,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = validChunkedBody.size.toLong()), validResult)
        assertContentEquals(validChunkedBody, validOutput.toByteArray())

        val invalidOutput = ByteArrayOutputStream()
        val invalidResult = HttpBodyStreamCopier.copyChunked(
            input = ByteArrayInputStream("5 ; = value\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)),
            output = invalidOutput,
        )

        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 0,
                reason = HttpChunkedBodyMalformedReason.InvalidChunkSize,
            ),
            invalidResult,
        )
        assertContentEquals(ByteArray(0), invalidOutput.toByteArray())
    }

    @Test
    fun `reports malformed chunk line endings separately from size limits`() {
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyChunked(
            input = ByteArrayInputStream("5\rXhello\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)),
            output = output,
        )

        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 0,
                reason = HttpChunkedBodyMalformedReason.MalformedLineEnding,
            ),
            result,
        )
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `rejects horizontal tab in chunk extension metadata`() {
        listOf(
            "5\t;name=value\r\nhello\r\n0\r\n\r\n",
            "5;name=\"a\tb\"\r\nhello\r\n0\r\n\r\n",
        ).forEach { chunkedBody ->
            val output = ByteArrayOutputStream()

            val result = HttpBodyStreamCopier.copyChunked(
                input = ByteArrayInputStream(chunkedBody.toByteArray(Charsets.US_ASCII)),
                output = output,
            )

            assertEquals(
                HttpBodyStreamCopyResult.MalformedChunk(
                    bytesCopied = 0,
                    reason = HttpChunkedBodyMalformedReason.InvalidChunkSize,
                ),
                result,
            )
            assertContentEquals(ByteArray(0), output.toByteArray())
        }
    }

    @Test
    fun `zero-length body does not read from the input stream`() {
        val input = CountingInputStream("body".toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyFixedLength(
            input = input,
            output = output,
            contentLength = 0,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 0), result)
        assertEquals(0, input.readCalls)
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `returns premature end without hiding already copied bytes`() {
        val input = ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()

        val result = HttpBodyStreamCopier.copyFixedLength(
            input = input,
            output = output,
            contentLength = 5,
        )

        assertEquals(
            HttpBodyStreamCopyResult.PrematureEnd(
                bytesCopied = 3,
                expectedBytes = 5,
            ),
            result,
        )
        assertEquals("abc", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects invalid copy arguments`() {
        assertFailsWith<IllegalArgumentException> {
            HttpBodyStreamCopier.copyFixedLength(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                contentLength = -1,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyStreamCopier.copyFixedLength(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                contentLength = 0,
                bufferSize = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyStreamCopier.copyChunked(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                bufferSize = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyStreamCopier.copyCloseDelimited(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                bufferSize = 0,
            )
        }
    }

    private class CountingInputStream(
        private val delegate: ByteArrayInputStream,
    ) : InputStream() {
        var readCalls: Int = 0
            private set

        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))

        override fun read(): Int {
            readCalls += 1
            return delegate.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readCalls += 1
            return delegate.read(buffer, offset, length)
        }
    }
}
