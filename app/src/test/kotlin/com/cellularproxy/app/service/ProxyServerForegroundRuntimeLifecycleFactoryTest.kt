package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.audit.ManagementApiAuditOutcome
import com.cellularproxy.app.audit.ManagementApiAuditRecord
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.network.BoundSocketProvider
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundConnectTunnelOpenResult
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenFailure
import com.cellularproxy.proxy.forwarding.OutboundHttpOriginOpenResult
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.management.ManagementApiResponse
import com.cellularproxy.proxy.management.ManagementApiStreamExchangeDisposition
import com.cellularproxy.proxy.server.BoundProxyServerSocket
import com.cellularproxy.proxy.server.ProxyBoundClientConnectionHandler
import com.cellularproxy.proxy.server.ProxyClientStreamExchangeHandler
import com.cellularproxy.proxy.server.ProxyServerSocketBindResult
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.proxy.ProxyStartupError
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyServerForegroundRuntimeLifecycleFactoryTest {
    @Test
    fun `created lifecycle starts proxy runtime with saved config secrets and observed networks`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        var boundListener: BoundProxyServerSocket? = null
        var observedNetworkCalls = 0
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = {
                observedNetworkCalls += 1
                listOf(wifiRoute())
            },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                    if (result is ProxyServerSocketBindResult.Bound) {
                        boundListener = result.listener
                    }
                }
            },
        )

        try {
            lifecycle.startProxyRuntime()

            val listener = assertNotNull(boundListener)
            Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                socket.getOutputStream().write(unauthenticatedProxyRequestBytes())
                socket.getOutputStream().flush()

                assertEquals(
                    "HTTP/1.1 407 Proxy Authentication Required",
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                )
            }

            assertEquals(1, observedNetworkCalls)
            assertTrue(lifecycle.hasRunningRuntime)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle uses current observed networks during startup preflight`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        var attemptedBind = false
        var currentNetworks = emptyList<NetworkDescriptor>()
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { currentNetworks },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                attemptedBind = true
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            val failure = assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                lifecycle.startProxyRuntime()
            }

            assertEquals(ProxyStartupError.UnavailableSelectedRoute, failure.startupError)
            assertEquals(false, attemptedBind)

            currentNetworks = listOf(wifiRoute())
            lifecycle.startProxyRuntime()
            assertEquals(true, attemptedBind)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle fails startup when enabled cloudflare has stale token-present metadata`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        var attemptedBind = false
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                cloudflare = AppConfig.default().cloudflare.copy(
                    enabled = true,
                    tunnelTokenPresent = true,
                ),
            ),
            sensitiveConfig = sensitiveConfig.copy(cloudflareTunnelToken = null),
            observedNetworks = { listOf(wifiRoute()) },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { _: String, _: Int, _: Int ->
                attemptedBind = true
                error("missing cloudflare tunnel token must fail before binding")
            },
        )

        try {
            val failure = assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                lifecycle.startProxyRuntime()
            }

            assertEquals(ProxyStartupError.MissingCloudflareTunnelToken, failure.startupError)
            assertEquals(false, attemptedBind)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle maps invalid maximum concurrent connections to startup failure before binding`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        var attemptedBind = false
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                proxy = loopbackAppConfig().proxy.copy(maxConcurrentConnections = 0),
            ),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { _: String, _: Int, _: Int ->
                attemptedBind = true
                error("invalid maximum concurrent connections must fail before binding")
            },
        )

        try {
            val failure = assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                lifecycle.startProxyRuntime()
            }

            assertEquals(ProxyStartupError.InvalidMaxConcurrentConnections, failure.startupError)
            assertEquals(false, attemptedBind)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle maps invalid override maximum concurrent connections to startup failure before binding`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        var attemptedBind = false
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            maxConcurrentConnections = 0,
            bindListener = { _: String, _: Int, _: Int ->
                attemptedBind = true
                error("invalid maximum concurrent connections override must fail before binding")
            },
        )

        try {
            val failure = assertFailsWith<ProxyServerForegroundRuntimeStartException> {
                lifecycle.startProxyRuntime()
            }

            assertEquals(ProxyStartupError.InvalidMaxConcurrentConnections, failure.startupError)
            assertEquals(false, attemptedBind)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle starts when enabled cloudflare token exists despite stale absent metadata`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                cloudflare = AppConfig.default().cloudflare.copy(
                    enabled = true,
                    tunnelTokenPresent = false,
                ),
            ),
            sensitiveConfig = sensitiveConfig.copy(cloudflareTunnelToken = "cloudflare-token"),
            observedNetworks = { listOf(wifiRoute()) },
            connectionHandler = connectionHandler(),
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()

            assertTrue(lifecycle.hasRunningRuntime)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle can own route-bound outbound connectors for proxy traffic`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        FakeOriginServer().use { origin ->
            val provider = RecordingBoundSocketProvider(origin)
            var boundListener: BoundProxyServerSocket? = null
            val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
                plainConfig = loopbackAppConfig().copy(
                    network = AppConfig.default().network.copy(
                        defaultRoutePolicy = RouteTarget.Cellular,
                    ),
                ),
                sensitiveConfig = sensitiveConfig,
                observedNetworks = { listOf(cellularRoute()) },
                socketProvider = provider,
                managementHandler = {
                    ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                        if (result is ProxyServerSocketBindResult.Bound) {
                            boundListener = result.listener
                        }
                    }
                },
            )

            try {
                lifecycle.startProxyRuntime()

                val listener = assertNotNull(boundListener)
                Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                    socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                    socket.getOutputStream().write(authenticatedProxyRequestBytes())
                    socket.getOutputStream().flush()

                    assertEquals(
                        "HTTP/1.1 200 OK",
                        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                    )
                }

                assertEquals(
                    BoundConnectCall(RouteTarget.Cellular, "origin.example.test", 18080, 30_000),
                    provider.awaitCall(),
                )
                assertEquals("GET /resource HTTP/1.1", origin.awaitRequestLine())
                assertNull(origin.failure.get())
            } finally {
                lifecycle.close()
                acceptLoopExecutor.shutdownNow()
                workerExecutor.shutdownNow()
                queuedClientTimeoutExecutor.shutdownNow()
                assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `created lifecycle can own route-bound outbound connectors for connect tunnel traffic`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        FakeTunnelOriginServer().use { origin ->
            val provider = RecordingBoundSocketProvider(origin.port)
            var boundListener: BoundProxyServerSocket? = null
            val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
                plainConfig = loopbackAppConfig().copy(
                    network = AppConfig.default().network.copy(
                        defaultRoutePolicy = RouteTarget.Cellular,
                    ),
                ),
                sensitiveConfig = sensitiveConfig,
                observedNetworks = { listOf(cellularRoute()) },
                socketProvider = provider,
                managementHandler = {
                    ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                        if (result is ProxyServerSocketBindResult.Bound) {
                            boundListener = result.listener
                        }
                    }
                },
            )

            try {
                lifecycle.startProxyRuntime()

                val listener = assertNotNull(boundListener)
                Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                    socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                    socket.getOutputStream().write(authenticatedConnectRequestBytes())
                    socket.getOutputStream().flush()

                    assertEquals(
                        "HTTP/1.1 200 Connection Established",
                        socket.getInputStream().readAsciiLine(),
                    )
                    assertEquals("", socket.getInputStream().readAsciiLine())

                    socket.getOutputStream().write("client tunnel bytes".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()

                    assertEquals(
                        "origin tunnel bytes",
                        socket.getInputStream().readRemainingAscii(),
                    )
                }

                assertEquals(
                    BoundConnectCall(RouteTarget.Cellular, "origin.example.test", 18443, 30_000),
                    provider.awaitCall(),
                )
                assertEquals("client tunnel bytes", origin.awaitReceivedBytes())
                assertNull(origin.failure.get())
            } finally {
                lifecycle.close()
                acceptLoopExecutor.shutdownNow()
                workerExecutor.shutdownNow()
                queuedClientTimeoutExecutor.shutdownNow()
                assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `created lifecycle can compose real route-bound socket provider from observed networks`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        FakeOriginServer().use { origin ->
            val connector = RecordingBoundNetworkSocketConnector(origin)
            var boundListener: BoundProxyServerSocket? = null
            val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
                plainConfig = loopbackAppConfig().copy(
                    network = AppConfig.default().network.copy(
                        defaultRoutePolicy = RouteTarget.Cellular,
                    ),
                ),
                sensitiveConfig = sensitiveConfig,
                observedNetworks = { listOf(wifiRoute(), cellularRoute()) },
                socketConnector = connector,
                managementHandler = {
                    ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                        if (result is ProxyServerSocketBindResult.Bound) {
                            boundListener = result.listener
                        }
                    }
                },
            )

            try {
                lifecycle.startProxyRuntime()

                val listener = assertNotNull(boundListener)
                Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                    socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                    socket.getOutputStream().write(authenticatedProxyRequestBytes())
                    socket.getOutputStream().flush()

                    assertEquals(
                        "HTTP/1.1 200 OK",
                        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                    )
                }

                assertEquals(
                    BoundNetworkConnectCall(cellularRoute(), "origin.example.test", 18080, 30_000),
                    connector.awaitCall(),
                )
                assertEquals("GET /resource HTTP/1.1", origin.awaitRequestLine())
                assertNull(origin.failure.get())
            } finally {
                lifecycle.close()
                acceptLoopExecutor.shutdownNow()
                workerExecutor.shutdownNow()
                queuedClientTimeoutExecutor.shutdownNow()
                assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `created lifecycle can compose route-bound connector with runtime-backed management reference`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        FakeOriginServer().use { origin ->
            val connector = RecordingBoundNetworkSocketConnector(origin)
            val managementReference = RuntimeManagementApiHandlerReference()
            var boundListener: BoundProxyServerSocket? = null
            val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
                plainConfig = loopbackAppConfig().copy(
                    network = AppConfig.default().network.copy(
                        defaultRoutePolicy = RouteTarget.Cellular,
                    ),
                ),
                sensitiveConfig = sensitiveConfig,
                observedNetworks = { listOf(wifiRoute(), cellularRoute()) },
                socketConnector = connector,
                managementHandlerReference = managementReference,
                publicIp = { "203.0.113.20" },
                cloudflareStatus = { CloudflareTunnelStatus.connected() },
                cloudflareStart = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Duplicate,
                        CloudflareTunnelStatus.connected(),
                    )
                },
                cloudflareStop = {
                    CloudflareTunnelTransitionResult(
                        CloudflareTunnelTransitionDisposition.Accepted,
                        CloudflareTunnelStatus.stopped(),
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
                rootAvailability = { RootAvailabilityStatus.Unknown },
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                        if (result is ProxyServerSocketBindResult.Bound) {
                            boundListener = result.listener
                        }
                    }
                },
            )

            try {
                assertEquals(503, managementReference.handle(ManagementApiOperation.Status).statusCode)

                lifecycle.startProxyRuntime()

                val listener = assertNotNull(boundListener)
                Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                    socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                    socket.getOutputStream().write(authenticatedProxyRequestBytes())
                    socket.getOutputStream().flush()

                    assertEquals(
                        "HTTP/1.1 200 OK",
                        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine(),
                    )
                }

                assertEquals(
                    BoundNetworkConnectCall(cellularRoute(), "origin.example.test", 18080, 30_000),
                    connector.awaitCall(),
                )
                assertEquals("GET /resource HTTP/1.1", origin.awaitRequestLine())

                val statusResponse = managementReference.handle(ManagementApiOperation.Status)
                assertEquals(200, statusResponse.statusCode)
                assertContains(statusResponse.body, """"state":"running"""")
                assertContains(statusResponse.body, """"publicIp":"203.0.113.20"""")
                assertNull(origin.failure.get())
            } finally {
                lifecycle.close()
                acceptLoopExecutor.shutdownNow()
                workerExecutor.shutdownNow()
                queuedClientTimeoutExecutor.shutdownNow()
                assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
                assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `created lifecycle records local high impact management audit events`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        val auditRecords = mutableListOf<ManagementApiAuditRecord>()
        var boundListener: BoundProxyServerSocket? = null
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
            publicIp = { null },
            cloudflareStatus = { CloudflareTunnelStatus.connected() },
            cloudflareStart = {
                CloudflareTunnelTransitionResult(
                    CloudflareTunnelTransitionDisposition.Duplicate,
                    CloudflareTunnelStatus.connected(),
                )
            },
            cloudflareStop = {
                CloudflareTunnelTransitionResult(
                    CloudflareTunnelTransitionDisposition.Ignored,
                    CloudflareTunnelStatus.connected(),
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
            rootAvailability = { RootAvailabilityStatus.Unknown },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            recordManagementAudit = auditRecords::add,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog).also { result ->
                    if (result is ProxyServerSocketBindResult.Bound) {
                        boundListener = result.listener
                    }
                }
            },
        )

        try {
            lifecycle.startProxyRuntime()

            val listener = assertNotNull(boundListener)
            Socket(TEST_LOOPBACK_HOST, listener.listenPort).use { socket ->
                socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                socket.getOutputStream().write(authenticatedManagementRequestBytes("POST /api/cloudflare/start HTTP/1.1"))
                socket.getOutputStream().flush()

                val input = socket.getInputStream()
                assertContains(
                    input.readAsciiLine(),
                    "HTTP/1.1 409 ",
                )
                input.readRemainingAscii()
            }

            assertEquals(
                listOf(
                    ManagementApiAuditRecord(
                        operation = ManagementApiOperation.CloudflareStart,
                        outcome = ManagementApiAuditOutcome.Responded,
                        statusCode = 409,
                        disposition = ManagementApiStreamExchangeDisposition.Routed,
                    ),
                ),
                auditRecords.toList(),
            )
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle installs runtime-backed management handler while runtime is running`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
            publicIp = { "203.0.113.10" },
            cloudflareStatus = { CloudflareTunnelStatus.connected() },
            cloudflareStart = {
                CloudflareTunnelTransitionResult(
                    CloudflareTunnelTransitionDisposition.Duplicate,
                    CloudflareTunnelStatus.connected(),
                )
            },
            cloudflareStop = {
                CloudflareTunnelTransitionResult(
                    CloudflareTunnelTransitionDisposition.Accepted,
                    CloudflareTunnelStatus.stopped(),
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
            rootAvailability = { RootAvailabilityStatus.Unknown },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            assertEquals(503, managementReference.handle(ManagementApiOperation.Status).statusCode)

            lifecycle.startProxyRuntime()

            val statusResponse = managementReference.handle(ManagementApiOperation.Status)
            assertEquals(200, statusResponse.statusCode)
            assertContains(statusResponse.body, """"state":"running"""")
            assertContains(statusResponse.body, """"publicIp":"203.0.113.10"""")
            assertContains(statusResponse.body, """"state":"connected"""")

            lifecycle.stopProxyRuntime()
            assertTrue(lifecycle.awaitPendingStopForTesting(1_000))
            assertEquals(503, managementReference.handle(ManagementApiOperation.Status).statusCode)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle blocks root rotation callbacks when root operations are disabled`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        var mobileDataRotationCalls = 0
        var airplaneModeRotationCalls = 0
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                root = AppConfig.default().root.copy(operationsEnabled = false),
            ),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
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
                mobileDataRotationCalls += 1
                RotationTransitionResult(
                    RotationTransitionDisposition.Accepted,
                    RotationStatus.idle(),
                )
            },
            rotateAirplaneMode = {
                airplaneModeRotationCalls += 1
                RotationTransitionResult(
                    RotationTransitionDisposition.Accepted,
                    RotationStatus.idle(),
                )
            },
            rootAvailability = { RootAvailabilityStatus.Unknown },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()

            val mobileResponse = managementReference.handle(ManagementApiOperation.RotateMobileData)
            val airplaneResponse = managementReference.handle(ManagementApiOperation.RotateAirplaneMode)

            assertEquals(409, mobileResponse.statusCode)
            assertEquals(409, airplaneResponse.statusCode)
            assertContains(mobileResponse.body, """"disposition":"rejected"""")
            assertContains(airplaneResponse.body, """"disposition":"rejected"""")
            assertContains(mobileResponse.body, """"failureReason":"root_operations_disabled"""")
            assertContains(airplaneResponse.body, """"failureReason":"root_operations_disabled"""")
            assertEquals(0, mobileDataRotationCalls)
            assertEquals(0, airplaneModeRotationCalls)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle rechecks root operation opt-in for running runtime management requests`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        var rootOperationsEnabled = true
        var mobileDataRotationCalls = 0
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                root = AppConfig.default().root.copy(operationsEnabled = true),
            ),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
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
                mobileDataRotationCalls += 1
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
            rootOperationsEnabled = { rootOperationsEnabled },
            rootAvailability = { RootAvailabilityStatus.Unknown },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()

            assertContains(
                managementReference.handle(ManagementApiOperation.Status).body,
                """"root":{"operationsEnabled":true,"availability":"unknown"}""",
            )
            assertEquals(409, managementReference.handle(ManagementApiOperation.RotateMobileData).statusCode)
            rootOperationsEnabled = false
            assertContains(
                managementReference.handle(ManagementApiOperation.Status).body,
                """"root":{"operationsEnabled":false,"availability":"unknown"}""",
            )
            val disabledResponse = managementReference.handle(ManagementApiOperation.RotateMobileData)

            assertEquals(409, disabledResponse.statusCode)
            assertContains(disabledResponse.body, """"disposition":"rejected"""")
            assertContains(disabledResponse.body, """"failureReason":"root_operations_disabled"""")
            assertEquals(1, mobileDataRotationCalls)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle rechecks root availability for running runtime management status`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        var rootAvailability = RootAvailabilityStatus.Unavailable
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                root = AppConfig.default().root.copy(operationsEnabled = true),
            ),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
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
            rootAvailability = { rootAvailability },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()

            assertContains(
                managementReference.handle(ManagementApiOperation.Status).body,
                """"root":{"operationsEnabled":true,"availability":"unavailable"}""",
            )

            rootAvailability = RootAvailabilityStatus.Available

            assertContains(
                managementReference.handle(ManagementApiOperation.Status).body,
                """"root":{"operationsEnabled":true,"availability":"available"}""",
            )
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle allows root rotation callbacks when root operations are enabled`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        var mobileDataRotationCalls = 0
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig().copy(
                root = AppConfig.default().root.copy(operationsEnabled = true),
            ),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
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
                mobileDataRotationCalls += 1
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
            rootAvailability = { RootAvailabilityStatus.Available },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()

            managementReference.handle(ManagementApiOperation.RotateMobileData)

            assertEquals(1, mobileDataRotationCalls)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `created lifecycle unregisters runtime-backed management handler on close`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val managementReference = RuntimeManagementApiHandlerReference()
        val lifecycle = ProxyServerForegroundRuntimeLifecycleFactory.create(
            plainConfig = loopbackAppConfig(),
            sensitiveConfig = sensitiveConfig,
            observedNetworks = { listOf(wifiRoute()) },
            socketProvider = RecordingUnavailableBoundSocketProvider,
            managementHandlerReference = managementReference,
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
            rootAvailability = { RootAvailabilityStatus.Unknown },
            workerExecutor = workerExecutor,
            queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
            acceptLoopExecutor = acceptLoopExecutor,
            bindListener = { listenHost: String, _: Int, backlog: Int ->
                ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
            },
        )

        try {
            lifecycle.startProxyRuntime()
            assertEquals(200, managementReference.handle(ManagementApiOperation.Status).statusCode)

            lifecycle.close()

            assertEquals(503, managementReference.handle(ManagementApiOperation.Status).statusCode)
        } finally {
            lifecycle.close()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private val sensitiveConfig = SensitiveConfig(
        proxyCredential = ProxyCredential(
            username = "proxy-user",
            password = "proxy-password",
        ),
        managementApiToken = "management-token",
    )

    private fun loopbackAppConfig(): AppConfig =
        AppConfig.default().copy(
            proxy = AppConfig.default().proxy.copy(
                listenHost = TEST_LOOPBACK_HOST,
                listenPort = 8080,
            ),
        )

    private fun connectionHandler(): ProxyBoundClientConnectionHandler =
        ProxyBoundClientConnectionHandler(
            exchangeHandler = ProxyClientStreamExchangeHandler(
                httpConnector = {
                    OutboundHttpOriginOpenResult.Failed(OutboundHttpOriginOpenFailure.SelectedRouteUnavailable)
                },
                connectConnector = {
                    OutboundConnectTunnelOpenResult.Failed(OutboundConnectTunnelOpenFailure.SelectedRouteUnavailable)
                },
                managementHandler = {
                    ManagementApiResponse.json(statusCode = 200, body = "{}")
                },
            ),
        )

    private fun unauthenticatedProxyRequestBytes(): ByteArray =
        (
            "GET http://example.com/resource HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

    private fun authenticatedProxyRequestBytes(): ByteArray {
        val credentials = Base64.getEncoder()
            .encodeToString("proxy-user:proxy-password".toByteArray(Charsets.UTF_8))
        return (
            "GET http://origin.example.test:18080/resource HTTP/1.1\r\n" +
                "Host: ignored.example.test\r\n" +
            "Proxy-Authorization: Basic $credentials\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun authenticatedConnectRequestBytes(): ByteArray {
        val credentials = Base64.getEncoder()
            .encodeToString("proxy-user:proxy-password".toByteArray(Charsets.UTF_8))
        return (
            "CONNECT origin.example.test:18443 HTTP/1.1\r\n" +
                "Host: origin.example.test:18443\r\n" +
                "Proxy-Authorization: Basic $credentials\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun authenticatedManagementRequestBytes(requestLine: String): ByteArray =
        (
            "$requestLine\r\n" +
                "Host: phone.local\r\n" +
                "Authorization: Bearer management-token\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

    private fun wifiRoute(): NetworkDescriptor =
        NetworkDescriptor(
            id = "wifi",
            category = NetworkCategory.WiFi,
            displayName = "Home Wi-Fi",
            isAvailable = true,
        )

    private fun cellularRoute(): NetworkDescriptor =
        NetworkDescriptor(
            id = "cellular",
            category = NetworkCategory.Cellular,
            displayName = "Cellular",
            isAvailable = true,
        )

    private class RecordingBoundSocketProvider(
        private val originPort: Int,
    ) : BoundSocketProvider {
        constructor(origin: FakeOriginServer) : this(origin.port)

        private val callLatch = CountDownLatch(1)
        private val call = AtomicReference<BoundConnectCall>()

        override suspend fun connect(
            route: RouteTarget,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult {
            call.set(BoundConnectCall(route, host, port, timeoutMillis))
            callLatch.countDown()
            return BoundSocketConnectResult.Connected(
                socket = Socket(TEST_LOOPBACK_HOST, originPort),
                network = NetworkDescriptor(
                    id = "cellular",
                    category = NetworkCategory.Cellular,
                    displayName = "Cellular",
                    isAvailable = true,
                ),
            )
        }

        fun awaitCall(): BoundConnectCall {
            assertTrue(callLatch.await(1, TimeUnit.SECONDS))
            return assertNotNull(call.get())
        }
    }

    private data class BoundConnectCall(
        val route: RouteTarget,
        val host: String,
        val port: Int,
        val timeoutMillis: Long,
    )

    private object RecordingUnavailableBoundSocketProvider : BoundSocketProvider {
        override suspend fun connect(
            route: RouteTarget,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult =
            BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
    }

    private class RecordingBoundNetworkSocketConnector(
        private val origin: FakeOriginServer,
    ) : BoundNetworkSocketConnector {
        private val callLatch = CountDownLatch(1)
        private val call = AtomicReference<BoundNetworkConnectCall>()

        override suspend fun connect(
            network: NetworkDescriptor,
            host: String,
            port: Int,
            timeoutMillis: Long,
        ): BoundSocketConnectResult {
            call.set(BoundNetworkConnectCall(network, host, port, timeoutMillis))
            callLatch.countDown()
            return BoundSocketConnectResult.Connected(
                socket = Socket(TEST_LOOPBACK_HOST, origin.port),
                network = network,
            )
        }

        fun awaitCall(): BoundNetworkConnectCall {
            assertTrue(callLatch.await(1, TimeUnit.SECONDS))
            return assertNotNull(call.get())
        }
    }

    private data class BoundNetworkConnectCall(
        val network: NetworkDescriptor,
        val host: String,
        val port: Int,
        val timeoutMillis: Long,
    )

    private class FakeOriginServer : AutoCloseable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName(TEST_LOOPBACK_HOST))
        private val requestLineLatch = CountDownLatch(1)
        private val requestLine = AtomicReference<String>()
        val failure = AtomicReference<Throwable?>()
        val port: Int = server.localPort
        private val executor = Executors.newSingleThreadExecutor()

        init {
            executor.execute {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        requestLine.set(reader.readLine())
                        requestLineLatch.countDown()
                        while (reader.readLine().isNotEmpty()) {
                            // Consume headers before sending the origin response.
                        }
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: 2\r\n" +
                                    "Connection: close\r\n" +
                                    "\r\n" +
                                    "ok"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        socket.getOutputStream().flush()
                    }
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                    requestLineLatch.countDown()
                }
            }
        }

        fun awaitRequestLine(): String {
            assertTrue(requestLineLatch.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Origin server failed", it) }
            return assertNotNull(requestLine.get())
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private class FakeTunnelOriginServer : AutoCloseable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName(TEST_LOOPBACK_HOST))
        private val receivedLatch = CountDownLatch(1)
        private val receivedBytes = AtomicReference<String>()
        private val acceptedSocket = AtomicReference<Socket?>()
        val failure = AtomicReference<Throwable?>()
        val port: Int = server.localPort
        private val executor = Executors.newSingleThreadExecutor()

        init {
            executor.execute {
                try {
                    server.accept().also { acceptedSocket.set(it) }.use { socket ->
                        socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
                        receivedBytes.set(
                            socket.getInputStream().readExactAscii(
                                expectedByteCount = "client tunnel bytes".length,
                            ),
                        )
                        receivedLatch.countDown()
                        socket.getOutputStream().write(
                            "origin tunnel bytes".toByteArray(Charsets.US_ASCII),
                        )
                        socket.getOutputStream().flush()
                    }
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                    receivedLatch.countDown()
                }
            }
        }

        fun awaitReceivedBytes(): String {
            assertTrue(receivedLatch.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Tunnel origin server failed", it) }
            return assertNotNull(receivedBytes.get())
        }

        override fun close() {
            server.close()
            acceptedSocket.getAndSet(null)?.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}

private const val CLIENT_READ_TIMEOUT_MILLIS = 1_000
private const val TEST_LOOPBACK_HOST = "127.0.0.1"

private fun InputStream.readAsciiLine(): String {
    val output = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value == -1) {
            break
        }
        if (value == '\n'.code) {
            break
        }
        if (value != '\r'.code) {
            output.write(value)
        }
    }
    return output.toString(Charsets.US_ASCII)
}

private fun InputStream.readRemainingAscii(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64)
    while (true) {
        val read = read(buffer)
        if (read == -1) {
            return output.toString(Charsets.US_ASCII)
        }
        output.write(buffer, 0, read)
    }
}

private fun InputStream.readExactAscii(expectedByteCount: Int): String {
    val bytes = ByteArray(expectedByteCount)
    var offset = 0
    while (offset < expectedByteCount) {
        val read = read(bytes, offset, expectedByteCount - offset)
        if (read == -1) {
            break
        }
        offset += read
    }
    return bytes.copyOf(offset).toString(Charsets.US_ASCII)
}
