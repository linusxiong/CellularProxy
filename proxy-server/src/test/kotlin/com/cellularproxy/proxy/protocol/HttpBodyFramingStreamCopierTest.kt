package com.cellularproxy.proxy.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpBodyFramingStreamCopierTest {
    @Test
    fun `request no-body framing completes without reading input`() {
        val input = CountingInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyRequestBody(
            framing = HttpRequestBodyFraming.NoBody,
            input = input,
            output = output,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 0), result)
        assertEquals(0, input.readCalls)
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `request fixed-length framing copies exactly the declared bytes`() {
        val input = ByteArrayInputStream("helloNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyRequestBody(
            framing = HttpRequestBodyFraming.FixedLength(5),
            input = input,
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 5), result)
        assertEquals("hello", output.toString(Charsets.US_ASCII))
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `request fixed-length framing returns premature end unchanged`() {
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyRequestBody(
            framing = HttpRequestBodyFraming.FixedLength(5),
            input = ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.PrematureEnd(bytesCopied = 3, expectedBytes = 5), result)
        assertEquals("abc", output.toString(Charsets.US_ASCII))
    }

    @Test
    fun `response no-body framing completes without reading input`() {
        val input = CountingInputStream("NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.NoBody,
            input = input,
            output = output,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 0), result)
        assertEquals(0, input.readCalls)
        assertContentEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun `response fixed-length framing copies exactly the declared bytes`() {
        val input = ByteArrayInputStream("helloNEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.FixedLength(5),
            input = input,
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = 5), result)
        assertEquals("hello", output.toString(Charsets.US_ASCII))
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `response fixed-length framing returns premature end unchanged`() {
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.FixedLength(5),
            input = ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.PrematureEnd(bytesCopied = 3, expectedBytes = 5), result)
        assertEquals("abc", output.toString(Charsets.US_ASCII))
    }

    @Test
    fun `response chunked framing copies validated chunked wire bytes`() {
        val chunkedBody = "5\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(chunkedBody + "NEXT".toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.Chunked,
            input = input,
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = chunkedBody.size.toLong()), result)
        assertContentEquals(chunkedBody, output.toByteArray())
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `response chunked framing returns premature end unchanged`() {
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.Chunked,
            input = ByteArrayInputStream("5\r\nabc".toByteArray(Charsets.US_ASCII)),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.ChunkedPrematureEnd(bytesCopied = 6), result)
        assertEquals("5\r\nabc", output.toString(Charsets.US_ASCII))
    }

    @Test
    fun `response chunked framing returns malformed chunk unchanged`() {
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.Chunked,
            input = ByteArrayInputStream("Z\r\nNEXT".toByteArray(Charsets.US_ASCII)),
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
    fun `response chunked framing forwards chunk header and trailer limits`() {
        val oversizedChunkHeaderOutput = ByteArrayOutputStream()
        val oversizedChunkHeader = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.Chunked,
            input = ByteArrayInputStream("5;long=value\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)),
            output = oversizedChunkHeaderOutput,
            maxChunkHeaderBytes = 4,
        )

        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 0,
                reason = HttpChunkedBodyMalformedReason.ChunkHeaderTooLarge,
            ),
            oversizedChunkHeader,
        )
        assertContentEquals(ByteArray(0), oversizedChunkHeaderOutput.toByteArray())

        val oversizedTrailerOutput = ByteArrayOutputStream()
        val oversizedTrailer = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.Chunked,
            input = ByteArrayInputStream("0\r\nX-Test: value\r\n\r\n".toByteArray(Charsets.US_ASCII)),
            output = oversizedTrailerOutput,
            maxTrailerBytes = 4,
        )

        assertEquals(
            HttpBodyStreamCopyResult.MalformedChunk(
                bytesCopied = 3,
                reason = HttpChunkedBodyMalformedReason.TrailerSectionTooLarge,
            ),
            oversizedTrailer,
        )
        assertEquals("0\r\n", oversizedTrailerOutput.toString(Charsets.US_ASCII))
    }

    @Test
    fun `response close-delimited framing copies until end of stream`() {
        val body = byteArrayOf(0, 1, 2, 127, (-1).toByte())
        val output = ByteArrayOutputStream()

        val result = HttpBodyFramingStreamCopier.copyResponseBody(
            framing = HttpResponseBodyFraming.CloseDelimited,
            input = ByteArrayInputStream(body),
            output = output,
            bufferSize = 2,
        )

        assertEquals(HttpBodyStreamCopyResult.Completed(bytesCopied = body.size.toLong()), result)
        assertContentEquals(body, output.toByteArray())
    }

    @Test
    fun `rejects invalid buffer sizes before copying`() {
        assertFailsWith<IllegalArgumentException> {
            HttpBodyFramingStreamCopier.copyRequestBody(
                framing = HttpRequestBodyFraming.NoBody,
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                bufferSize = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyFramingStreamCopier.copyResponseBody(
                framing = HttpResponseBodyFraming.NoBody,
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                bufferSize = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyFramingStreamCopier.copyResponseBody(
                framing = HttpResponseBodyFraming.NoBody,
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                maxChunkHeaderBytes = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            HttpBodyFramingStreamCopier.copyResponseBody(
                framing = HttpResponseBodyFraming.NoBody,
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                maxTrailerBytes = -1,
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
