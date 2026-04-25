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

    data class ChunkedPrematureEnd(val bytesCopied: Long) : HttpBodyStreamCopyResult {
        init {
            require(bytesCopied >= 0) { "Copied byte count must be non-negative" }
        }
    }

    data class MalformedChunk(
        val bytesCopied: Long,
        val reason: HttpChunkedBodyMalformedReason,
    ) : HttpBodyStreamCopyResult {
        init {
            require(bytesCopied >= 0) { "Copied byte count must be non-negative" }
        }
    }
}

enum class HttpChunkedBodyMalformedReason {
    InvalidChunkSize,
    ChunkHeaderTooLarge,
    MalformedLineEnding,
    InvalidChunkDataTerminator,
    InvalidTrailer,
    TrailerSectionTooLarge,
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

    fun copyChunked(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BODY_BUFFER_BYTES,
        maxChunkHeaderBytes: Int = DEFAULT_CHUNK_HEADER_BYTES,
        maxTrailerBytes: Int = DEFAULT_TRAILER_BYTES,
    ): HttpBodyStreamCopyResult {
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(maxChunkHeaderBytes > 0) { "Maximum chunk header bytes must be positive" }
        require(maxTrailerBytes >= 0) { "Maximum trailer bytes must be non-negative" }

        var copied = 0L
        while (true) {
            val chunkHeader = input.readCrlfLine(maxLineBytes = maxChunkHeaderBytes)
            when (chunkHeader) {
                is ChunkLineReadResult.EndOfStream -> return HttpBodyStreamCopyResult.ChunkedPrematureEnd(copied)
                is ChunkLineReadResult.LineTooLarge -> {
                    return HttpBodyStreamCopyResult.MalformedChunk(
                        bytesCopied = copied,
                        reason = HttpChunkedBodyMalformedReason.ChunkHeaderTooLarge,
                    )
                }
                is ChunkLineReadResult.MalformedLineEnding -> {
                    return HttpBodyStreamCopyResult.MalformedChunk(
                        bytesCopied = copied,
                        reason = HttpChunkedBodyMalformedReason.MalformedLineEnding,
                    )
                }
                is ChunkLineReadResult.Completed -> {
                    val chunkSize = chunkHeader.lineBytes.decodeChunkSizeOrNull()
                        ?: return HttpBodyStreamCopyResult.MalformedChunk(
                            bytesCopied = copied,
                            reason = HttpChunkedBodyMalformedReason.InvalidChunkSize,
                        )

                    output.write(chunkHeader.wireBytes)
                    copied += chunkHeader.wireBytes.size.toLong()

                    if (chunkSize == 0L) {
                        return copyChunkedTrailers(
                            input = input,
                            output = output,
                            bytesCopied = copied,
                            maxTrailerBytes = maxTrailerBytes,
                        )
                    }

                    val bodyCopyResult = copyChunkData(
                        input = input,
                        output = output,
                        chunkSize = chunkSize,
                        bufferSize = bufferSize,
                    )
                    copied += bodyCopyResult.bytesCopied
                    if (!bodyCopyResult.completed) {
                        return HttpBodyStreamCopyResult.ChunkedPrematureEnd(copied)
                    }

                    val terminator = input.readExactCrlf()
                    when (terminator) {
                        ChunkCrlfReadResult.Completed -> {
                            output.write(CRLF_BYTES)
                            copied += CRLF_BYTES.size.toLong()
                        }
                        ChunkCrlfReadResult.EndOfStream -> {
                            return HttpBodyStreamCopyResult.ChunkedPrematureEnd(copied)
                        }
                        ChunkCrlfReadResult.Malformed -> {
                            return HttpBodyStreamCopyResult.MalformedChunk(
                                bytesCopied = copied,
                                reason = HttpChunkedBodyMalformedReason.InvalidChunkDataTerminator,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun copyChunkedTrailers(
        input: InputStream,
        output: OutputStream,
        bytesCopied: Long,
        maxTrailerBytes: Int,
    ): HttpBodyStreamCopyResult {
        var copied = bytesCopied
        var trailerBytes = 0
        while (true) {
            val remainingTrailerBytes = maxTrailerBytes - trailerBytes
            if (remainingTrailerBytes < CRLF_BYTES.size) {
                return HttpBodyStreamCopyResult.MalformedChunk(
                    bytesCopied = copied,
                    reason = HttpChunkedBodyMalformedReason.TrailerSectionTooLarge,
                )
            }

            val trailerLine = input.readCrlfLine(maxLineBytes = remainingTrailerBytes)
            when (trailerLine) {
                is ChunkLineReadResult.EndOfStream -> return HttpBodyStreamCopyResult.ChunkedPrematureEnd(copied)
                is ChunkLineReadResult.LineTooLarge -> {
                    return HttpBodyStreamCopyResult.MalformedChunk(
                        bytesCopied = copied,
                        reason = HttpChunkedBodyMalformedReason.TrailerSectionTooLarge,
                    )
                }
                is ChunkLineReadResult.MalformedLineEnding -> {
                    return HttpBodyStreamCopyResult.MalformedChunk(
                        bytesCopied = copied,
                        reason = HttpChunkedBodyMalformedReason.MalformedLineEnding,
                    )
                }
                is ChunkLineReadResult.Completed -> {
                    if (trailerLine.lineBytes.isEmpty()) {
                        output.write(trailerLine.wireBytes)
                        copied += trailerLine.wireBytes.size.toLong()
                        return HttpBodyStreamCopyResult.Completed(bytesCopied = copied)
                    }

                    if (!trailerLine.lineBytes.isValidTrailerLine()) {
                        return HttpBodyStreamCopyResult.MalformedChunk(
                            bytesCopied = copied,
                            reason = HttpChunkedBodyMalformedReason.InvalidTrailer,
                        )
                    }

                    output.write(trailerLine.wireBytes)
                    copied += trailerLine.wireBytes.size.toLong()
                    trailerBytes += trailerLine.wireBytes.size
                }
            }
        }
    }

    private fun copyChunkData(
        input: InputStream,
        output: OutputStream,
        chunkSize: Long,
        bufferSize: Int,
    ): ChunkDataCopyResult {
        val buffer = ByteArray(bufferSize)
        var copied = 0L
        while (copied < chunkSize) {
            val remaining = chunkSize - copied
            val readLimit = minOf(buffer.size.toLong(), remaining).toInt()
            val readBytes = input.read(buffer, 0, readLimit)
            if (readBytes == END_OF_STREAM) {
                return ChunkDataCopyResult(bytesCopied = copied, completed = false)
            }
            output.write(buffer, 0, readBytes)
            copied += readBytes.toLong()
        }
        return ChunkDataCopyResult(bytesCopied = copied, completed = true)
    }
}

private const val DEFAULT_BODY_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_CHUNK_HEADER_BYTES = 8 * 1024
private const val DEFAULT_TRAILER_BYTES = 16 * 1024
private const val END_OF_STREAM = -1
private const val CARRIAGE_RETURN = '\r'.code
private const val LINE_FEED = '\n'.code
private const val DELETE_CONTROL_CHAR = 0x7F
private const val HEX_RADIX = 16
private val CRLF_BYTES = byteArrayOf(CARRIAGE_RETURN.toByte(), LINE_FEED.toByte())
private val HTTP_TOKEN_CHARS = (
    ('0'..'9') +
        ('A'..'Z') +
        ('a'..'z') +
        listOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
).map { it.code.toByte() }.toSet()

private sealed interface ChunkLineReadResult {
    data class Completed(val lineBytes: ByteArray) : ChunkLineReadResult {
        val wireBytes: ByteArray = lineBytes + CRLF_BYTES
    }
    data object EndOfStream : ChunkLineReadResult
    data object LineTooLarge : ChunkLineReadResult
    data object MalformedLineEnding : ChunkLineReadResult
}

private enum class ChunkCrlfReadResult {
    Completed,
    EndOfStream,
    Malformed,
}

private data class ChunkDataCopyResult(
    val bytesCopied: Long,
    val completed: Boolean,
)

private fun InputStream.readCrlfLine(maxLineBytes: Int): ChunkLineReadResult {
    val line = ArrayList<Byte>()
    while (true) {
        val next = read()
        if (next == END_OF_STREAM) {
            return ChunkLineReadResult.EndOfStream
        }
        if (line.size + CRLF_BYTES.size > maxLineBytes) {
            return ChunkLineReadResult.LineTooLarge
        }
        if (next == CARRIAGE_RETURN) {
            val following = read()
            return when (following) {
                LINE_FEED -> ChunkLineReadResult.Completed(line.toByteArray())
                END_OF_STREAM -> ChunkLineReadResult.EndOfStream
                else -> ChunkLineReadResult.MalformedLineEnding
            }
        }
        line.add(next.toByte())
    }
}

private fun InputStream.readExactCrlf(): ChunkCrlfReadResult {
    val first = read()
    if (first == END_OF_STREAM) {
        return ChunkCrlfReadResult.EndOfStream
    }
    if (first != CARRIAGE_RETURN) {
        return ChunkCrlfReadResult.Malformed
    }

    val second = read()
    return when (second) {
        LINE_FEED -> ChunkCrlfReadResult.Completed
        END_OF_STREAM -> ChunkCrlfReadResult.EndOfStream
        else -> ChunkCrlfReadResult.Malformed
    }
}

private fun ByteArray.decodeChunkSizeOrNull(): Long? {
    if (any { byte -> byte.toInt() !in 0x00..0x7F }) {
        return null
    }
    val line = toString(Charsets.US_ASCII)
    val sizeEnd = line.indexOfFirst { !it.isHexDigit() }.let { if (it == -1) line.length else it }
    if (sizeEnd == 0) {
        return null
    }

    var index = sizeEnd
    while (index < line.length && line[index].isChunkWhitespace()) {
        index += 1
    }

    while (index < line.length) {
        if (line[index] != ';') {
            return null
        }
        index += 1

        while (index < line.length && line[index].isChunkWhitespace()) {
            index += 1
        }

        val nameStart = index
        while (index < line.length && line[index].isHttpToken()) {
            index += 1
        }
        if (index == nameStart) {
            return null
        }

        while (index < line.length && line[index].isChunkWhitespace()) {
            index += 1
        }

        if (index < line.length && line[index] == '=') {
            index += 1
            while (index < line.length && line[index].isChunkWhitespace()) {
                index += 1
            }

            val valueEnd = line.parseChunkExtensionValueEnd(startIndex = index)
                ?: return null
            if (valueEnd == index) {
                return null
            }
            index = valueEnd

            while (index < line.length && line[index].isChunkWhitespace()) {
                index += 1
            }
        }
    }

    return line.substring(0, sizeEnd).toLongOrNull(radix = HEX_RADIX)
}

private fun ByteArray.isValidTrailerLine(): Boolean {
    val separator = indexOf(':'.code.toByte())
    if (separator <= 0) {
        return false
    }
    val name = copyOfRange(0, separator)
    val value = copyOfRange(separator + 1, size)
    return name.isHttpToken() && value.none { it.isDisallowedHeaderValueControl() }
}

private fun ByteArray.isHttpToken(): Boolean =
    isNotEmpty() && all { it in HTTP_TOKEN_CHARS }

private fun Char.isHttpToken(): Boolean =
    code.toByte() in HTTP_TOKEN_CHARS

private fun Char.isChunkWhitespace(): Boolean =
    this == ' '

private fun String.parseChunkExtensionValueEnd(startIndex: Int): Int? {
    if (startIndex >= length) {
        return null
    }

    if (this[startIndex] != '"') {
        var index = startIndex
        while (index < length && this[index].isHttpToken()) {
            index += 1
        }
        return index
    }

    var index = startIndex + 1
    while (index < length) {
        val char = this[index]
        when {
            char == '"' -> return index + 1
            char == '\\' -> {
                index += 1
                if (index >= length || !this[index].isQuotedPairChar()) {
                    return null
                }
            }
            !char.isQuotedTextChar() -> return null
        }
        index += 1
    }
    return null
}

private fun Char.isQuotedTextChar(): Boolean =
    this == ' ' || this == '!' || this in '#'..'[' || this in ']'..'~'

private fun Char.isQuotedPairChar(): Boolean =
    this == ' ' || this in '!'..'~'

private fun Byte.isDisallowedHeaderValueControl(): Boolean {
    val unsigned = toInt() and 0xFF
    return unsigned in 0x00..0x1F || unsigned == DELETE_CONTROL_CHAR
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
