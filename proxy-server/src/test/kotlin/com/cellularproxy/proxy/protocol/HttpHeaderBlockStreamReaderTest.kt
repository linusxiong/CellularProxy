package com.cellularproxy.proxy.protocol

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpHeaderBlockStreamReaderTest {
    @Test
    fun `reads CRLF terminated header block without consuming following body bytes`() {
        val input =
            ByteArrayInputStream(
                "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\nbody"
                    .toByteArray(Charsets.US_ASCII),
            )

        val result = HttpHeaderBlockStreamReader.read(input)

        assertEquals(
            HttpHeaderBlockStreamReadResult.Completed(
                headerBlock = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n",
                bytesRead = 55,
            ),
            result,
        )
        assertEquals('b'.code, input.read())
    }

    @Test
    fun `reads LF terminated header block`() {
        val input =
            ByteArrayInputStream(
                "HTTP/1.1 200 OK\nContent-Length: 0\n\nNEXT".toByteArray(Charsets.US_ASCII),
            )

        val result = HttpHeaderBlockStreamReader.read(input)

        assertEquals(
            HttpHeaderBlockStreamReadResult.Completed(
                headerBlock = "HTTP/1.1 200 OK\nContent-Length: 0\n\n",
                bytesRead = 35,
            ),
            result,
        )
        assertEquals('N'.code, input.read())
    }

    @Test
    fun `reports incomplete header block when stream ends before terminator`() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: example.com".toByteArray(Charsets.US_ASCII))

        val result = HttpHeaderBlockStreamReader.read(input)

        assertEquals(
            HttpHeaderBlockStreamReadResult.Incomplete(bytesRead = 33),
            result,
        )
    }

    @Test
    fun `reports oversized header block at the configured limit without consuming following bytes`() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.US_ASCII))

        val result = HttpHeaderBlockStreamReader.read(input, maxHeaderBytes = 16)

        assertEquals(
            HttpHeaderBlockStreamReadResult.HeaderBlockTooLarge(bytesRead = 16),
            result,
        )
        assertEquals('H'.code, input.read())
    }

    @Test
    fun `reports malformed header encoding instead of replacing invalid UTF-8 bytes`() {
        val invalidUtf8HeaderBlock =
            byteArrayOf(
                'G'.code.toByte(),
                'E'.code.toByte(),
                'T'.code.toByte(),
                ' '.code.toByte(),
                '/'.code.toByte(),
                ' '.code.toByte(),
                'H'.code.toByte(),
                'T'.code.toByte(),
                'T'.code.toByte(),
                'P'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(),
                '.'.code.toByte(),
                '1'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                'X'.code.toByte(),
                '-'.code.toByte(),
                'N'.code.toByte(),
                'a'.code.toByte(),
                'm'.code.toByte(),
                'e'.code.toByte(),
                ':'.code.toByte(),
                ' '.code.toByte(),
                0xC3.toByte(),
                0x28.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
        val input = ByteArrayInputStream(invalidUtf8HeaderBlock + "NEXT".toByteArray(Charsets.US_ASCII))

        val result = HttpHeaderBlockStreamReader.read(input)

        assertEquals(
            HttpHeaderBlockStreamReadResult.MalformedHeaderEncoding(bytesRead = invalidUtf8HeaderBlock.size),
            result,
        )
        assertEquals('N'.code, input.read())
    }
}
