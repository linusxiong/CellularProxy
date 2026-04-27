package com.cellularproxy.proxy.protocol

import java.io.InputStream
import java.io.OutputStream

object HttpBodyFramingStreamCopier {
    fun copyRequestBody(
        framing: HttpRequestBodyFraming,
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BODY_BUFFER_BYTES,
    ): HttpBodyStreamCopyResult {
        require(bufferSize > 0) { "Buffer size must be positive" }

        return when (framing) {
            HttpRequestBodyFraming.NoBody -> HttpBodyStreamCopyResult.Completed(bytesCopied = 0)
            is HttpRequestBodyFraming.FixedLength ->
                HttpBodyStreamCopier.copyFixedLength(
                    input = input,
                    output = output,
                    contentLength = framing.contentLength,
                    bufferSize = bufferSize,
                )
        }
    }

    fun copyResponseBody(
        framing: HttpResponseBodyFraming,
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BODY_BUFFER_BYTES,
        maxChunkHeaderBytes: Int = DEFAULT_CHUNK_HEADER_BYTES,
        maxTrailerBytes: Int = DEFAULT_TRAILER_BYTES,
    ): HttpBodyStreamCopyResult {
        require(bufferSize > 0) { "Buffer size must be positive" }
        require(maxChunkHeaderBytes > 0) { "Maximum chunk header bytes must be positive" }
        require(maxTrailerBytes >= 0) { "Maximum trailer bytes must be non-negative" }

        return when (framing) {
            HttpResponseBodyFraming.NoBody -> HttpBodyStreamCopyResult.Completed(bytesCopied = 0)
            is HttpResponseBodyFraming.FixedLength ->
                HttpBodyStreamCopier.copyFixedLength(
                    input = input,
                    output = output,
                    contentLength = framing.contentLength,
                    bufferSize = bufferSize,
                )
            HttpResponseBodyFraming.Chunked ->
                HttpBodyStreamCopier.copyChunked(
                    input = input,
                    output = output,
                    bufferSize = bufferSize,
                    maxChunkHeaderBytes = maxChunkHeaderBytes,
                    maxTrailerBytes = maxTrailerBytes,
                )
            HttpResponseBodyFraming.CloseDelimited ->
                HttpBodyStreamCopier.copyCloseDelimited(
                    input = input,
                    output = output,
                    bufferSize = bufferSize,
                )
        }
    }
}
