package com.cellularproxy.cloudflare

import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlane
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneSnapshot
import com.cellularproxy.shared.cloudflare.CloudflareTunnelControlPlaneTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelEvent
import com.cellularproxy.shared.cloudflare.CloudflareTunnelGuardedTransitionResult
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import java.util.concurrent.CancellationException

class CloudflareTunnelConnectionCoordinator(
    private val controlPlane: CloudflareTunnelControlPlane,
    private val connector: CloudflareTunnelEdgeConnector,
    private val sessionRegistry: CloudflareTunnelEdgeSessionStore? = null,
) {
    fun connectIfStarting(
        expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelConnectionCoordinatorResult {
        val currentSnapshot = controlPlane.snapshot()
        if (currentSnapshot != expectedSnapshot) {
            return CloudflareTunnelConnectionCoordinatorResult.Stale(
                expectedSnapshot = expectedSnapshot,
                actualSnapshot = currentSnapshot,
            )
        }
        if (expectedSnapshot.status.state != CloudflareTunnelState.Starting) {
            return CloudflareTunnelConnectionCoordinatorResult.NoAction(currentSnapshot)
        }

        val connectionResult = connectToCloudflareEdge(connector, credentials)
        val event = connectionResult.toCloudflareTunnelEvent()

        var connectionToClose: CloudflareTunnelEdgeConnection? = null
        val result =
            synchronized(controlPlane) {
                when (val guarded = controlPlane.apply(expectedSnapshot, event)) {
                    is CloudflareTunnelGuardedTransitionResult.Evaluated -> {
                        if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                            connectionToClose =
                                sessionRegistry?.installConnectedSession(
                                    snapshot = guarded.transition.snapshot,
                                    connection = connectionResult.connection,
                                )
                        }
                        CloudflareTunnelConnectionCoordinatorResult.Applied(
                            connectionResult = connectionResult,
                            transition = guarded.transition,
                        )
                    }
                    is CloudflareTunnelGuardedTransitionResult.Stale -> {
                        if (connectionResult is CloudflareTunnelEdgeConnectionResult.Connected) {
                            connectionToClose = connectionResult.connection
                        }
                        CloudflareTunnelConnectionCoordinatorResult.Stale(
                            expectedSnapshot = guarded.expectedSnapshot,
                            actualSnapshot = guarded.actualSnapshot,
                        )
                    }
                }
            }
        connectionToClose?.closeSuppressingExceptions()
        return result
    }
}

fun interface CloudflareTunnelEdgeConnector {
    fun connect(credentials: CloudflareTunnelCredentials): CloudflareTunnelEdgeConnectionResult
}

fun interface CloudflareTunnelEdgeConnection {
    fun close()
}

sealed interface CloudflareTunnelEdgeConnectionResult {
    data class Connected(
        val connection: CloudflareTunnelEdgeConnection,
    ) : CloudflareTunnelEdgeConnectionResult {
        override fun toString(): String = "CloudflareTunnelEdgeConnectionResult.Connected(connection=<redacted>)"
    }

    data class Failed(
        val failure: CloudflareTunnelEdgeConnectionFailure,
    ) : CloudflareTunnelEdgeConnectionResult
}

enum class CloudflareTunnelEdgeConnectionFailure {
    EdgeUnavailable,
    AuthenticationRejected,
    ProtocolError,
}

sealed interface CloudflareTunnelConnectionCoordinatorResult {
    data class Applied(
        val connectionResult: CloudflareTunnelEdgeConnectionResult,
        val transition: CloudflareTunnelControlPlaneTransitionResult,
    ) : CloudflareTunnelConnectionCoordinatorResult {
        init {
            require(transition.accepted) {
                "Applied Cloudflare tunnel connection result must contain an accepted transition"
            }
        }
    }

    data class Stale(
        val expectedSnapshot: CloudflareTunnelControlPlaneSnapshot,
        val actualSnapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelConnectionCoordinatorResult

    data class NoAction(
        val snapshot: CloudflareTunnelControlPlaneSnapshot,
    ) : CloudflareTunnelConnectionCoordinatorResult
}

internal fun connectToCloudflareEdge(
    connector: CloudflareTunnelEdgeConnector,
    credentials: CloudflareTunnelCredentials,
): CloudflareTunnelEdgeConnectionResult =
    try {
        connector.connect(credentials)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.ProtocolError)
    }

internal fun CloudflareTunnelEdgeConnectionResult.toCloudflareTunnelEvent(): CloudflareTunnelEvent =
    when (this) {
        is CloudflareTunnelEdgeConnectionResult.Connected -> CloudflareTunnelEvent.Connected
        is CloudflareTunnelEdgeConnectionResult.Failed -> CloudflareTunnelEvent.Failed(failure.name)
    }

internal fun CloudflareTunnelEdgeSessionStore.installConnectedSession(
    snapshot: CloudflareTunnelControlPlaneSnapshot,
    connection: CloudflareTunnelEdgeConnection,
): CloudflareTunnelEdgeConnection? {
    val replacedSession = install(snapshot, connection).replacedSession
    return if (replacedSession?.connection !== connection) {
        replacedSession?.connection
    } else {
        null
    }
}

internal fun CloudflareTunnelEdgeConnection.closeSuppressingExceptions() {
    try {
        close()
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        // Stale connection cleanup is best effort; the stale lifecycle result remains authoritative.
    }
}
