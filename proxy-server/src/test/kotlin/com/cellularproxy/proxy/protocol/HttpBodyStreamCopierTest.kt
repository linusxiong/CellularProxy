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
