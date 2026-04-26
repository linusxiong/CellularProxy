package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.proxy.management.ManagementApiHandler
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.shared.cloudflare.CloudflareTunnelState
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionDisposition
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyServerRuntimeManagementCallbacksTest {
    @Test
    fun `runtime management callbacks expose live status and request real runtime stop`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val running = startRuntime(
            listener = listener,
            acceptLoopExecutor = acceptLoopExecutor,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        )

        try {
            val callbacks = ProxyServerRuntimeManagementCallbacks.create(
                runtime = running,
                networks = { emptyList() },
                publicIp = { null },
                cloudflareStatus = { CloudflareTunnelStatus.disabled() },
                cloudflareStart = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                cloudflareStop = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                rotateMobileData = {
                    RotationTransitionResult(
                        RotationTransitionDisposition.Ignored,
                        RotationStatus.idle(),
                    )
                },
                rotateAirplaneMode = {
                    RotationTransitionResult(
                        RotationTransitionDisposition.Ignored,
                        RotationStatus.idle(),
                    )
                },
            )

            assertEquals(ProxyServiceState.Running, callbacks.healthStatus().state)
            assertEquals(ProxyServiceState.Running, callbacks.status().state)

            val stopResult = callbacks.serviceStop()

            assertEquals(ProxyServiceStopTransitionDisposition.Accepted, stopResult.disposition)
            assertEquals(ProxyServiceState.Stopping, stopResult.status.state)
            assertEquals(ProxyServiceState.Stopping, callbacks.status().state)
            assertTrue(listener.isClosed)

            val stopped = assertIs<ProxyServerRuntimeStopResult.Finished>(
                running.awaitStopped(timeoutMillis = 1_000),
            )
            assertIs<ProxyBoundServerAcceptLoopResult.Stopped>(stopped.result)
            assertEquals(ProxyServiceState.Stopped, callbacks.status().state)
        } finally {
            running.stop()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `runtime management callbacks delegate non-runtime management state providers`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val running = startRuntime(
            listener = listener,
            acceptLoopExecutor = acceptLoopExecutor,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        )

        try {
            val network = NetworkDescriptor("cell-1", NetworkCategory.Cellular, "Carrier LTE", true)
            val cloudflareStart = CloudflareTunnelTransitionResult(
                CloudflareTunnelTransitionDisposition.Accepted,
                CloudflareTunnelStatus.starting(),
            )
            val cloudflareStop = CloudflareTunnelTransitionResult(
                CloudflareTunnelTransitionDisposition.Duplicate,
                CloudflareTunnelStatus.stopped(),
            )
            val mobileRotation = RotationTransitionResult(
                RotationTransitionDisposition.Accepted,
                RotationStatus(state = RotationState.CheckingCooldown, operation = RotationOperation.MobileData),
            )
            val airplaneRotation = RotationTransitionResult(
                RotationTransitionDisposition.Duplicate,
                RotationStatus(state = RotationState.CheckingCooldown, operation = RotationOperation.AirplaneMode),
            )

            val callbacks = ProxyServerRuntimeManagementCallbacks.create(
                runtime = running,
                networks = { listOf(network) },
                publicIp = { "203.0.113.42" },
                cloudflareStatus = { CloudflareTunnelStatus.connected() },
                cloudflareStart = { cloudflareStart },
                cloudflareStop = { cloudflareStop },
                rotateMobileData = { mobileRotation },
                rotateAirplaneMode = { airplaneRotation },
            )

            assertEquals(listOf(network), callbacks.networks())
            assertEquals("203.0.113.42", callbacks.publicIp())
            assertEquals(CloudflareTunnelState.Connected, callbacks.cloudflareStatus().state)
            assertEquals("203.0.113.42", callbacks.status().publicIp)
            assertEquals(CloudflareTunnelState.Connected, callbacks.status().cloudflare.state)
            assertEquals(cloudflareStart, callbacks.cloudflareStart())
            assertEquals(cloudflareStop, callbacks.cloudflareStop())
            assertEquals(mobileRotation, callbacks.rotateMobileData())
            assertEquals(airplaneRotation, callbacks.rotateAirplaneMode())
        } finally {
            running.stop()
            assertIs<ProxyServerRuntimeStopResult.Finished>(running.awaitStopped(timeoutMillis = 1_000))
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `runtime management callbacks can start rotations through the shared control plane`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val running = startRuntime(
            listener = listener,
            acceptLoopExecutor = acceptLoopExecutor,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        )

        try {
            val controlPlane = RotationControlPlane()
            val callbacks = ProxyServerRuntimeManagementCallbacks.create(
                runtime = running,
                networks = { emptyList() },
                publicIp = { null },
                cloudflareStatus = { CloudflareTunnelStatus.disabled() },
                cloudflareStart = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                cloudflareStop = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                rotationControlPlane = controlPlane,
                nowElapsedMillis = { 10_000 },
                rotationCooldown = 180.seconds,
            )

            val mobileStart = callbacks.rotateMobileData()
            val duplicateAirplaneStart = callbacks.rotateAirplaneMode()

            assertEquals(RotationTransitionDisposition.Accepted, mobileStart.disposition)
            assertEquals(RotationState.CheckingRoot, mobileStart.status.state)
            assertEquals(RotationOperation.MobileData, mobileStart.status.operation)
            assertEquals(mobileStart.status, controlPlane.currentStatus)
            assertEquals(RotationTransitionDisposition.Duplicate, duplicateAirplaneStart.disposition)
            assertEquals(mobileStart.status, duplicateAirplaneStart.status)
            assertEquals(mobileStart.status, controlPlane.currentStatus)
        } finally {
            running.stop()
            assertIs<ProxyServerRuntimeStopResult.Finished>(running.awaitStopped(timeoutMillis = 1_000))
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `runtime management callbacks apply rotation cooldown through the shared control plane`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val backingSocket = ServerSocket(0)
        val listener = BoundProxyServerSocket(backingSocket, LOOPBACK_HOST)
        val running = startRuntime(
            listener = listener,
            acceptLoopExecutor = acceptLoopExecutor,
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
        )

        try {
            val controlPlane = RotationControlPlane(initialLastTerminalElapsedMillis = 10_000)
            val callbacks = ProxyServerRuntimeManagementCallbacks.create(
                runtime = running,
                networks = { emptyList() },
                publicIp = { null },
                cloudflareStatus = { CloudflareTunnelStatus.disabled() },
                cloudflareStart = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                cloudflareStop = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Ignored,
                        CloudflareTunnelStatus.disabled(),
                    )
                },
                rotationControlPlane = controlPlane,
                nowElapsedMillis = { 10_100 },
                rotationCooldown = 180.seconds,
            )

            val mobileStart = callbacks.rotateMobileData()

            assertEquals(RotationTransitionDisposition.Accepted, mobileStart.disposition)
            assertEquals(RotationState.Failed, mobileStart.status.state)
            assertEquals(RotationOperation.MobileData, mobileStart.status.operation)
            assertEquals(RotationFailureReason.CooldownActive, mobileStart.status.failureReason)
            assertEquals(mobileStart.status, controlPlane.currentStatus)
            assertEquals(10_000, controlPlane.lastTerminalElapsedMillis)
        } finally {
            running.stop()
            assertIs<ProxyServerRuntimeStopResult.Finished>(running.awaitStopped(timeoutMillis = 1_000))
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private fun startRuntime(
        listener: BoundProxyServerSocket,
        acceptLoopExecutor: java.util.concurrent.ExecutorService,
        workerExecutor: java.util.concurrent.ExecutorService,
        queuedClientTimeoutExecutor: ScheduledThreadPoolExecutor,
    ): RunningProxyServerRuntime =
        assertIs<ProxyServerRuntimeResult.Running>(
            ProxyServerRuntime.start(
                config = AppConfig.default().copy(
                    proxy = AppConfig.default().proxy.copy(listenHost = LOOPBACK_HOST, listenPort = 8081),
                ),
                managementApiTokenPresent = true,
                observedNetworks = listOf(wifiRoute()),
                ingressConfig = ingressConfig(),
                connectionHandler = connectionHandler(),
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { _, _, _ -> ProxyServerSocketBindResult.Bound(listener) },
            ),
        ).runtime

    private fun ingressConfig(): ProxyIngressPreflightConfig =
        ProxyIngressPreflightConfig(
            connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 1),
            requestAdmission = ProxyRequestAdmissionConfig(
                proxyAuthentication = ProxyAuthenticationConfig(
                    authEnabled = false,
                    credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                ),
                managementApiToken = "management-token",
            ),
        )

    private fun connectionHandler(): ProxyBoundClientConnectionHandler =
        ProxyBoundClientConnectionHandler(
            exchangeHandler = ProxyClientStreamExchangeHandler(
                httpConnector = {
                    OutboundHttpOriginOpenResult.Failed(
                        OutboundHttpOriginOpenFailure.SelectedRouteUnavailable,
                    )
                },
                connectConnector = {
                    OutboundConnectTunnelOpenResult.Failed(
                        OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable,
                    )
                },
                managementHandler = object : ManagementApiHandler {
                    override fun handle(operation: ManagementApiOperation): ManagementApiResponse =
                        ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
            ),
        )

    private fun wifiRoute(): NetworkDescriptor =
        NetworkDescriptor(
            id = "wifi",
            category = NetworkCategory.WiFi,
            displayName = "Home Wi-Fi",
            isAvailable = true,
        )
}

private const val LOOPBACK_HOST = "127.0.0.1"
