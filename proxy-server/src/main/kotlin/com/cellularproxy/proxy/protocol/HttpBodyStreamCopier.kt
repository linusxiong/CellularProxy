package com.cellularproxy.proxy.protocol

import java.io.InputStream
import java.io.OutputStream

sealed interface HttpBodyStreamCopyResult {
    data class Completed(val bytesCopied: Long) : HttpBodyStreamCopyResult {
        init {
            require(bytesCopied >= 0) { "Copied byte count must be non-negative" }
        }
    }

    data class PrematureEnd(
        val bytesCopied: Long,
        val expectedBytes: Long,
    ) : HttpBodyStreamCopyResult {
        init {
            require(bytesCopied >= 0) { "Copied byte count must be non-negative" }
            require(expectedBytes >= 0) { "Expected byte count must be non-negative" }
            require(bytesCopied < expectedBytes) { "Premature end requires fewer copied bytes than expected" }
        }
    }
}

object HttpBodyStreamCopier {
    fun copyFixedLength(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        bufferSize: Int = DEFAULT_BODY_BUFFER_BYTES,
    ): HttpBodyStreamCopyResult {
        require(contentLength >= 0) { "Content length must be non-negative" }
        require(bufferSize > 0) { "Buffer size must be positive" }

        val buffer = ByteArray(bufferSize)
        var copied = 0L
        while (copied < contentLength) {
            val remaining = contentLength - copied
            val readLimit = minOf(buffer.size.toLong(), remaining).toInt()
            val readBytes = input.read(buffer, 0, readLimit)
            if (readBytes == END_OF_STREAM) {
                return HttpBodyStreamCopyResult.PrematureEnd(
                    bytesCopied = copied,
                    expectedBytes = contentLength,
                )
            }

            output.write(buffer, 0, readBytes)
            copied += readBytes.toLong()
        }

        return HttpBodyStreamCopyResult.Completed(bytesCopied = copied)
    }
}

private const val DEFAULT_BODY_BUFFER_BYTES = 8 * 1024
private const val END_OF_STREAM = -1
