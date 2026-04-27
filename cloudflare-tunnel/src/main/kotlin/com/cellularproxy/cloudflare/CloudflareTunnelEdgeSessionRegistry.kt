package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot

interface CloudflareTunnelEdgeSessionStore {
    fun install(
        snapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): CloudflareTunnelEdgeSessionInstallResult

    fun takeActiveConnection(): CloudflareTunnelEdgeConnection?
}

interface CloudflareTunnelEdgeCurrentSessionStore {
    fun isCurrent(
        snapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): Boolean

    fun updateSnapshotIfCurrent(
        currentSnapshot: CloudflareTunnelControlPlaneSnapshot,
        newSnapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): Boolean
}

class CloudflareTunnelEdgeSessionRegistry :
    CloudflareTunnelEdgeSessionStore,
    CloudflareTunnelEdgeCurrentSessionStore {
    private var activeSession: CloudflareTunnelEdgeSession? = null

    @Synchronized
    fun currentSessionOrNull(): CloudflareTunnelEdgeSession? = activeSession

    @Synchronized
    override fun install(
        snapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): CloudflareTunnelEdgeSessionInstallResult {
        val replacedSession = activeSession
        activeSession = CloudflareTunnelEdgeSession(snapshot, connection)
        return CloudflareTunnelEdgeSessionInstallResult(replacedSession)
    }

    @Synchronized
    fun takeIfCurrent(expectedSnapshot: CloudflareTunnelControlPlaneSnapshot): CloudflareTunnelEdgeConnection? {
        val session = activeSession ?: return null
        if (session.snapshot != expectedSnapshot) {
            return null
        }
        activeSession = null
        return session.connection
    }

    @Synchronized
    override fun takeActiveConnection(): CloudflareTunnelEdgeConnection? {
        val session = activeSession
        activeSession = null
        return session?.connection
    }

    @Synchronized
    override fun isCurrent(
        snapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): Boolean {
        val session = activeSession ?: return false
        return session.snapshot == snapshot && session.connection === connection
    }

    @Synchronized
    override fun updateSnapshotIfCurrent(
        currentSnapshot: CloudflareTunnelControlPlaneSnapshot,
        newSnapshot: CloudflareTunnelControlPlaneSnapshot,
        connection: CloudflareTunnelEdgeConnection,
    ): Boolean {
        val session = activeSession ?: return false
        if (session.snapshot != currentSnapshot || session.connection !== connection) {
            return false
        }
        activeSession = CloudflareTunnelEdgeSession(newSnapshot, connection)
        return true
    }

    @Synchronized
    fun clearIfCurrent(connection: CloudflareTunnelEdgeConnection): Boolean {
        val session = activeSession
        if (session?.connection !== connection) {
            return false
        }
        activeSession = null
        return true
    }

    @Synchronized
    override fun toString(): String =
        if (activeSession == null) {
            "CloudflareTunnelEdgeSessionRegistry(activeSession=null)"
        } else {
            "CloudflareTunnelEdgeSessionRegistry(activeSession=<redacted>)"
        }
}

class CloudflareTunnelEdgeSession internal constructor(
    val snapshot: CloudflareTunnelControlPlaneSnapshot,
    val connection: CloudflareTunnelEdgeConnection,
) {
    override fun toString(): String = "CloudflareTunnelEdgeSession(snapshot=$snapshot, connection=<redacted>)"
}

class CloudflareTunnelEdgeSessionInstallResult internal constructor(
    val replacedSession: CloudflareTunnelEdgeSession?,
) {
    override fun toString(): String =
        if (replacedSession == null) {
            "CloudflareTunnelEdgeSessionInstallResult(replacedSession=null)"
        } else {
            "CloudflareTunnelEdgeSessionInstallResult(replacedSession=<redacted>)"
        }
}
