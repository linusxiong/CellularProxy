package com.cellularproxy.root

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BlockingRootCommandProcessExecutor(
    private val charset: Charset = Charsets.UTF_8,
    private val streamCleanupTimeoutMillis: Long = DEFAULT_STREAM_CLEANUP_TIMEOUT_MILLIS,
) : RootCommandProcessExecutor {
    init {
        require(streamCleanupTimeoutMillis > 0) { "Stream cleanup timeout must be positive" }
    }

    override fun execute(
        command: RootShellCommand,
        timeoutMillis: Long,
    ): RootCommandProcessResult {
        require(timeoutMillis > 0) { "Root process timeout must be positive" }

        val process = ProcessBuilder(command.argv).start()
        process.outputStream.closeIgnoringFailure()
        val stdout = StreamCapture(process.inputStream, charset).also { it.start() }
        val stderr = StreamCapture(process.errorStream, charset).also { it.start() }

        val completed = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            cleanupAfterInterruption(process, stdout, stderr)
            Thread.currentThread().interrupt()
            throw exception
        }

        return if (completed) {
            try {
                RootCommandProcessResult.Completed(
                    exitCode = process.exitValue(),
                    stdout = stdout.await(streamCleanupTimeoutMillis),
                    stderr = stderr.await(streamCleanupTimeoutMillis),
                )
            } catch (exception: InterruptedException) {
                cleanupAfterInterruption(process, stdout, stderr)
                Thread.currentThread().interrupt()
                throw exception
            }
        } else {
            val descendants = process.descendants().toList()
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            closeProcessStreams(process, stdout, stderr)
            descendants.forEach { waitForDestroyedProcess(it, streamCleanupTimeoutMillis) }
            waitForDestroyedProcess(process, streamCleanupTimeoutMillis)
            RootCommandProcessResult.TimedOut(
                stdout = stdout.await(streamCleanupTimeoutMillis),
                stderr = stderr.await(streamCleanupTimeoutMillis),
            )
        }
    }

    private fun cleanupAfterInterruption(
        process: Process,
        stdout: StreamCapture,
        stderr: StreamCapture,
    ) {
        val descendants = process.descendants().toList()
        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        closeProcessStreams(process, stdout, stderr)
        descendants.forEach { waitForDestroyedProcessIgnoringInterruption(it, streamCleanupTimeoutMillis) }
        waitForDestroyedProcessIgnoringInterruption(process, streamCleanupTimeoutMillis)
    }

    private fun waitForDestroyedProcess(
        process: ProcessHandle,
        timeoutMillis: Long,
    ) {
        try {
            process.onExit().get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (_: TimeoutException) {
        } catch (_: ExecutionException) {
        }
    }

    private fun waitForDestroyedProcessIgnoringInterruption(
        process: ProcessHandle,
        timeoutMillis: Long,
    ) {
        try {
            process.onExit().get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } catch (_: TimeoutException) {
        } catch (_: ExecutionException) {
        }
    }

    private fun waitForDestroyedProcessIgnoringInterruption(
        process: Process,
        timeoutMillis: Long,
    ) {
        try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun waitForDestroyedProcess(
        process: Process,
        timeoutMillis: Long,
    ) {
        try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
    }

    private fun closeProcessStreams(
        process: Process,
        stdout: StreamCapture,
        stderr: StreamCapture,
    ) {
        stdout.close()
        stderr.close()
        process.outputStream.closeIgnoringFailure()
    }

    private fun java.io.Closeable.closeIgnoringFailure() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val DEFAULT_STREAM_CLEANUP_TIMEOUT_MILLIS = 250L
    }
}

private class StreamCapture(
    private val input: InputStream,
    private val charset: Charset,
) {
    private val output = ByteArrayOutputStream()
    @Volatile
    private var failure: Exception? = null
    @Volatile
    private var closedByOwner: Boolean = false
    private val thread = Thread {
        try {
            input.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    synchronized(output) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (exception: Exception) {
            failure = exception
        }
    }.apply {
        isDaemon = true
    }

    fun start() {
        thread.start()
    }

    fun await(timeoutMillis: Long): String {
        try {
            thread.join(timeoutMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
        if (thread.isAlive) {
            close()
            try {
                thread.join(timeoutMillis)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            }
        }
        failure?.let { if (!closedByOwner) throw it }
        return synchronized(output) {
            output.toByteArray().toString(charset)
        }
    }

    fun close() {
        closedByOwner = true
        try {
            input.close()
        } catch (_: Exception) {
        }
    }
}
