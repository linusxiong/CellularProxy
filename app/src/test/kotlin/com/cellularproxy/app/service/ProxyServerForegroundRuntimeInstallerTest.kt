package com.cellularproxy.app.service

import com.cellularproxy.app.config.AppConfigBootstrapResult
import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.app.config.SensitiveConfigInvalidReason
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.proxy.management.ManagementApiOperation
import com.cellularproxy.proxy.server.ProxyServerSocketBinder
import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProxyServerForegroundRuntimeInstallerTest {
    @AfterTest
    fun resetRegistry() {
        ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
    }

    @Test
    fun `ready bootstrap installs combined route-bound runtime lifecycle with management reference`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newCachedThreadPool()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        val result =
            ProxyServerForegroundRuntimeInstaller.install(
                bootstrapResult =
                    AppConfigBootstrapResult.Ready(
                        plainConfig = loopbackAppConfig(),
                        sensitiveConfig = sensitiveConfig,
                        createdDefaultSecrets = false,
                        reconciledPlainConfig = false,
                    ),
                observedNetworks = { listOf(wifiRoute()) },
                socketConnector = UnavailableBoundNetworkSocketConnector,
                publicIp = { "203.0.113.42" },
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
                rootAvailability = { RootAvailabilityStatus.Available },
                workerExecutor = workerExecutor,
                queuedClientTimeoutExecutor = queuedClientTimeoutExecutor,
                acceptLoopExecutor = acceptLoopExecutor,
                bindListener = { listenHost: String, _: Int, backlog: Int ->
                    ProxyServerSocketBinder.bindEphemeral(listenHost, backlog)
                },
            )

        try {
            val installed = assertIs<ProxyServerForegroundRuntimeInstallResult.Installed>(result)
            assertEquals(503, installed.managementHandlerReference.handle(ManagementApiOperation.Status).statusCode)

            ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle.startProxyRuntime()

            val statusResponse = installed.managementHandlerReference.handle(ManagementApiOperation.Status)
            assertEquals(200, statusResponse.statusCode)
            assertContains(statusResponse.body, """"state":"running"""")
            assertContains(statusResponse.body, """"publicIp":"203.0.113.42"""")
            assertContains(statusResponse.body, """"root":{"operationsEnabled":false,"availability":"unknown"}""")

            installed.registration.close()
            assertSame(
                UninstalledForegroundProxyRuntimeLifecycle,
                ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
            )
            assertEquals(503, installed.managementHandlerReference.handle(ManagementApiOperation.Status).statusCode)
        } finally {
            ForegroundProxyRuntimeLifecycleRegistry.resetForTesting()
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalid sensitive bootstrap result does not install runtime lifecycle`() {
        val acceptLoopExecutor = Executors.newSingleThreadExecutor()
        val workerExecutor = Executors.newSingleThreadExecutor()
        val queuedClientTimeoutExecutor = ScheduledThreadPoolExecutor(1)
        try {
            val result =
                ProxyServerForegroundRuntimeInstaller.install(
                    bootstrapResult =
                        AppConfigBootstrapResult.InvalidSensitiveConfig(
                            SensitiveConfigInvalidReason.UndecryptableSecret,
                        ),
                    observedNetworks = { listOf(wifiRoute()) },
                    socketConnector = UnavailableBoundNetworkSocketConnector,
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
                )

            assertEquals(
                ProxyServerForegroundRuntimeInstallResult.InvalidSensitiveConfig(
                    SensitiveConfigInvalidReason.UndecryptableSecret,
                ),
                result,
            )
            assertSame(
                UninstalledForegroundProxyRuntimeLifecycle,
                ForegroundProxyRuntimeLifecycleRegistry.foregroundProxyRuntimeLifecycle,
            )
        } finally {
            acceptLoopExecutor.shutdownNow()
            workerExecutor.shutdownNow()
            queuedClientTimeoutExecutor.shutdownNow()
            assertTrue(acceptLoopExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(queuedClientTimeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private val sensitiveConfig =
        SensitiveConfig(
            proxyCredential =
                ProxyCredential(
                    username = "proxy-user",
                    password = "proxy-password",
                ),
            managementApiToken = "management-token",
        )

    private fun loopbackAppConfig(): AppConfig = AppConfig.default().copy(
        proxy =
            AppConfig.default().proxy.copy(
                listenHost = "127.0.0.1",
                listenPort = 8080,
            ),
    )

    private fun wifiRoute(): NetworkDescriptor = NetworkDescriptor(
        id = "wifi",
        category = NetworkCategory.WiFi,
        displayName = "Home Wi-Fi",
        isAvailable = true,
    )
}

private object UnavailableBoundNetworkSocketConnector : BoundNetworkSocketConnector {
    override suspend fun connect(
        network: NetworkDescriptor,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult = BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
}
