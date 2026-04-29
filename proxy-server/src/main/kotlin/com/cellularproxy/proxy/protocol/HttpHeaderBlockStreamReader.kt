package com.cellularproxy.proxy.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed interface HttpHeaderBlockStreamReadResult {
    data class Completed(
        val headerBlock: String,
        val bytesRead: Int,
    ) : HttpHeaderBlockStreamReadResult {
        init {
            require(bytesRead >= 0) { "Bytes read must be non-negative" }
        }
    }

    data class Incomplete(
        val bytesRead: Int,
    ) : HttpHeaderBlockStreamReadResult {
        init {
            require(bytesRead >= 0) { "Bytes read must be non-negative" }
        }
    }

    data class HeaderBlockTooLarge(
        val bytesRead: Int,
    ) : HttpHeaderBlockStreamReadResult {
        init {
            require(bytesRead >= 0) { "Bytes read must be non-negative" }
        }
    }

    data class MalformedHeaderEncoding(
        val bytesRead: Int,
    ) : HttpHeaderBlockStreamReadResult {
        init {
            require(bytesRead >= 0) { "Bytes read must be non-negative" }
        }
    }
}

object HttpHeaderBlockStreamReader {
    fun read(
        input: InputStream,
        maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    ): HttpHeaderBlockStreamReadResult {
        require(maxHeaderBytes > 0) { "Maximum header bytes must be positive" }

        val headerBytes = ByteArrayOutputStream()
        var thirdPrevious = UNSET_BYTE
        var secondPrevious = UNSET_BYTE
        var previous = UNSET_BYTE
        while (true) {
            val next = input.read()
            if (next == END_OF_STREAM) {
                return HttpHeaderBlockStreamReadResult.Incomplete(bytesRead = headerBytes.size())
            }

            headerBytes.write(next)
            if (terminatesHeaderBlock(thirdPrevious, secondPrevious, previous, next)) {
                val bytes = headerBytes.toByteArray()
                val decodedHeaderBlock =
                    bytes.decodeStrictUtf8OrNull()
                        ?: return HttpHeaderBlockStreamReadResult.MalformedHeaderEncoding(bytesRead = bytes.size)
                return HttpHeaderBlockStreamReadResult.Completed(
                    headerBlock = decodedHeaderBlock,
                    bytesRead = bytes.size,
                )
            }

            if (headerBytes.size() >= maxHeaderBytes) {
                return HttpHeaderBlockStreamReadResult.HeaderBlockTooLarge(bytesRead = headerBytes.size())
            }

            thirdPrevious = secondPrevious
            secondPrevious = previous
            previous = next
        }
    }

    private fun terminatesHeaderBlock(
        thirdPrevious: Int,
        secondPrevious: Int,
        previous: Int,
        current: Int,
    ): Boolean = previous == LINE_FEED &&
        current == LINE_FEED ||
        thirdPrevious == CARRIAGE_RETURN &&
        secondPrevious == LINE_FEED &&
        previous == CARRIAGE_RETURN &&
        current == LINE_FEED
}

private fun ByteArray.decodeStrictUtf8OrNull(): String? = try {
    Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private const val DEFAULT_MAX_HEADER_BYTES = 16 * 1024
private const val END_OF_STREAM = -1
private const val UNSET_BYTE = -2
private const val CARRIAGE_RETURN = '\r'.code
private const val LINE_FEED = '\n'.code
