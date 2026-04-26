package com.cellularproxy.app.service

import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeManagementApiHandlerReference : ManagementApiHandler {
    private val lock = Any()
    private var currentEntry: RuntimeManagementApiHandlerEntry? = null

    fun install(handler: ManagementApiHandler): Closeable {
        val newEntry = RuntimeManagementApiHandlerEntry(handler)
        val previous = synchronized(lock) {
            val previousEntry = currentEntry
            currentEntry = newEntry
            previousEntry
        }
        previous?.closeSuppressingExceptions()
        return RuntimeManagementApiHandlerRegistration(newEntry)
    }

    override fun handle(operation: ManagementApiOperation): ManagementApiResponse {
        val handler = synchronized(lock) { currentEntry?.handler }
        return handler?.handle(operation) ?: unavailableResponse()
    }

    override fun toString(): String =
        "RuntimeManagementApiHandlerReference(installed=${synchronized(lock) { currentEntry != null }})"

    private inner class RuntimeManagementApiHandlerRegistration(
        private val entry: RuntimeManagementApiHandlerEntry,
    ) : Closeable {
        override fun close() {
            val removed = synchronized(lock) {
                if (currentEntry === entry) {
                    currentEntry = null
                    entry
                } else {
                    null
                }
            }
            removed?.close()
        }
    }

    private companion object {
        fun unavailableResponse(): ManagementApiResponse =
            ManagementApiResponse(
                statusCode = 503,
                reasonPhrase = "Service Unavailable",
                headers = linkedMapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Content-Length" to UNAVAILABLE_BODY.toByteArray(Charsets.UTF_8).size.toString(),
                    "Cache-Control" to "no-store",
                    "Connection" to "close",
                ),
                body = UNAVAILABLE_BODY,
            )
    }
}

private class RuntimeManagementApiHandlerEntry(
    val handler: ManagementApiHandler,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            (handler as? Closeable)?.close()
        }
    }
}

private fun RuntimeManagementApiHandlerEntry.closeSuppressingExceptions() {
    try {
        close()
    } catch (_: Throwable) {
        // Replacing the runtime handler must not leave callers without ownership
        // of the newly installed handler when old cleanup fails.
    }
}

private const val UNAVAILABLE_BODY = """{"error":"management_unavailable"}"""
