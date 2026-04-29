package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareTunnelEdgeSessionRegistryTest {
    @Test
    fun `new registry has no active session`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()

        assertNull(registry.currentSessionOrNull())
        assertNull(registry.takeActiveConnection())
    }

    @Test
    fun `install stores active session and take removes matching snapshot`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val snapshot = connectedSnapshot()
        val connection = NamedEdgeConnection("live-secret-connection")

        val installResult = registry.install(snapshot, connection)

        assertNull(installResult.replacedSession)
        assertEquals(snapshot, registry.currentSessionOrNull()?.snapshot)
        assertSame(connection, registry.currentSessionOrNull()?.connection)
        val taken = registry.takeIfCurrent(snapshot)
        assertSame(connection, taken)
        assertNull(registry.currentSessionOrNull())
    }

    @Test
    fun `installing replacement returns previous session without closing it`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val firstSnapshot = connectedSnapshot()
        val secondSnapshot = degradedSnapshot()
        val first = NamedEdgeConnection("first-secret-connection")
        val second = NamedEdgeConnection("second-secret-connection")
        registry.install(firstSnapshot, first)

        val replacement = registry.install(secondSnapshot, second)

        assertEquals(firstSnapshot, replacement.replacedSession?.snapshot)
        assertSame(first, replacement.replacedSession?.connection)
        assertEquals(secondSnapshot, registry.currentSessionOrNull()?.snapshot)
        assertSame(second, registry.currentSessionOrNull()?.connection)
        assertFalse(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `take if current only removes matching active snapshot`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val staleSnapshot = connectedSnapshot()
        val currentSnapshot = degradedSnapshot()
        val current = NamedEdgeConnection("current-secret-connection")
        registry.install(currentSnapshot, current)

        val staleTaken = registry.takeIfCurrent(staleSnapshot)

        assertNull(staleTaken)
        assertSame(current, registry.currentSessionOrNull()?.connection)
        val currentTaken = registry.takeIfCurrent(currentSnapshot)
        assertSame(current, currentTaken)
        assertNull(registry.currentSessionOrNull())
    }

    @Test
    fun `clear if current only removes matching active connection identity`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val snapshot = connectedSnapshot()
        val stale = NamedEdgeConnection("stale-secret-connection")
        val current = NamedEdgeConnection("current-secret-connection")
        registry.install(snapshot, current)

        val staleCleared = registry.clearIfCurrent(stale)

        assertFalse(staleCleared)
        assertSame(current, registry.currentSessionOrNull()?.connection)
        val currentCleared = registry.clearIfCurrent(current)
        assertTrue(currentCleared)
        assertNull(registry.currentSessionOrNull())
    }

    @Test
    fun `diagnostics do not expose connection details`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val firstSnapshot = connectedSnapshot()
        val secondSnapshot = degradedSnapshot()
        val first = NamedEdgeConnection("first-secret-connection")
        val second = NamedEdgeConnection("second-secret-connection")
        registry.install(firstSnapshot, first)

        val replacement = registry.install(secondSnapshot, second)

        assertEquals(
            "CloudflareTunnelEdgeSessionInstallResult(replacedSession=<redacted>)",
            replacement.toString(),
        )
        assertEquals("CloudflareTunnelEdgeSessionRegistry(activeSession=<redacted>)", registry.toString())
        assertFalse(replacement.toString().contains("first-secret-connection"))
        assertFalse(registry.toString().contains("second-secret-connection"))
    }

    @Test
    fun `active session summary reports snapshot state without exposing connection details`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()
        val snapshot = degradedSnapshot()
        registry.install(snapshot, NamedEdgeConnection("edge-session-secret"))

        val summary = registry.activeSessionSummaryOrNull()

        assertEquals("Active edge session: Degraded (generation 1)", summary)
        assertFalse(summary?.contains("edge-session-secret") ?: false)
    }

    @Test
    fun `active session summary is null without active session`() {
        val registry = CloudflareTunnelEdgeSessionRegistry()

        assertNull(registry.activeSessionSummaryOrNull())
    }

    private fun connectedSnapshot() = CloudflareTunnelControlPlane(CloudflareTunnelStatus.connected()).snapshot()

    private fun degradedSnapshot() = CloudflareTunnelControlPlane(CloudflareTunnelStatus.connected())
        .also { it.apply(CloudflareTunnelEvent.Degraded) }
        .snapshot()

    private class NamedEdgeConnection(
        private val diagnosticName: String,
    ) : CloudflareTunnelEdgeConnection {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }

        override fun toString(): String = diagnosticName
    }
}
