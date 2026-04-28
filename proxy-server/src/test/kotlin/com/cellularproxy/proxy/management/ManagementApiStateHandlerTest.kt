package com.cellularproxy.proxy.management

import com.cellularproxy.shared.cloudflare.CloudflareTunnelStatus
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionDisposition
import com.cellularproxy.shared.cloudflare.CloudflareTunnelTransitionResult
import com.cellularproxy.shared.config.RouteTarget
import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.proxy.ProxyServiceState
import com.cellularproxy.shared.proxy.ProxyServiceStatus
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionDisposition
import com.cellularproxy.shared.proxy.ProxyServiceStopTransitionResult
import com.cellularproxy.shared.proxy.ProxyTrafficMetrics
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagementApiStateHandlerTest {
    @Test
    fun `health uses only health status callback and read-only renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedStatus = stoppedStatus()
        callbacks.healthStatusResult = expectedStatus

        val response = callbacks.handler().handle(ManagementApiOperation.Health)

        assertSameResponse(ManagementApiReadOnlyResponses.health(expectedStatus), response)
        callbacks.assertCalls("healthStatus")
    }

    @Test
    fun `status uses only status callback and read-only renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedStatus = runningStatus()
        val secrets = LogRedactionSecrets(managementApiToken = "status-secret")
        callbacks.statusResult = expectedStatus
        callbacks.rootOperationsEnabledResult = true

        val response = callbacks.handler(secrets).handle(ManagementApiOperation.Status)

        assertSameResponse(
            ManagementApiReadOnlyResponses.status(
                status = expectedStatus,
                rootOperationsEnabled = true,
                secrets = secrets,
            ),
            response,
        )
        callbacks.assertCalls("rootOperationsEnabled", "status", "rotationStatus")
    }

    @Test
    fun `status suppresses root availability when root operations are disabled`() {
        val callbacks = RecordingCallbacks()
        callbacks.statusResult = runningStatus().copy(rootAvailability = RootAvailabilityStatus.Available)
        callbacks.rootOperationsEnabledResult = false

        val response = callbacks.handler().handle(ManagementApiOperation.Status)

        assertEquals(
            ManagementApiReadOnlyResponses
                .status(
                    status = callbacks.statusResult.copy(rootAvailability = RootAvailabilityStatus.Unknown),
                    rootOperationsEnabled = false,
                ).body,
            response.body,
        )
        callbacks.assertCalls("rootOperationsEnabled", "status", "rotationStatus")
    }

    @Test
    fun `status includes current rotation status from rotation callback`() {
        val callbacks = RecordingCallbacks()
        callbacks.statusResult = runningStatus()
        callbacks.rootOperationsEnabledResult = true
        callbacks.rotationStatusResult =
            RotationStatus(
                state = RotationState.WaitingForNetworkReturn,
                operation = RotationOperation.AirplaneMode,
                oldPublicIp = "198.51.100.10",
            )

        val response = callbacks.handler().handle(ManagementApiOperation.Status)

        assertEquals(
            """{"service":{"state":"running","listenHost":"0.0.0.0","listenPort":8080,"configuredRoute":"automatic","boundRoute":null,"publicIp":"198.51.100.10","highSecurityRisk":false,"startupError":null},"metrics":{"activeConnections":0,"totalConnections":0,"rejectedConnections":0,"bytesReceived":0,"bytesSent":0},"cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"failed with status-secret"},"root":{"operationsEnabled":true,"availability":"unknown"},"rotation":{"state":"waiting_for_network_return","operation":"airplane_mode","oldPublicIp":"198.51.100.10","newPublicIp":null,"publicIpChanged":null,"failureReason":null}}""",
            response.body,
        )
        callbacks.assertCalls("rootOperationsEnabled", "status", "rotationStatus")
    }

    @Test
    fun `networks uses only networks callback and read-only renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedNetworks = listOf(NetworkDescriptor("cell-1", NetworkCategory.Cellular, "Carrier LTE", true))
        callbacks.networksResult = expectedNetworks

        val response = callbacks.handler().handle(ManagementApiOperation.Networks)

        assertSameResponse(ManagementApiReadOnlyResponses.networks(expectedNetworks), response)
        callbacks.assertCalls("networks")
    }

    @Test
    fun `public ip uses only public ip callback and read-only renderer`() {
        val callbacks = RecordingCallbacks()
        callbacks.publicIpResult = "203.0.113.10"

        val response = callbacks.handler().handle(ManagementApiOperation.PublicIp)

        assertSameResponse(ManagementApiReadOnlyResponses.publicIp("203.0.113.10"), response)
        callbacks.assertCalls("publicIp")
    }

    @Test
    fun `cloudflare status uses only cloudflare status callback and read-only renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedStatus = CloudflareTunnelStatus.connected()
        val secrets = LogRedactionSecrets(cloudflareTunnelToken = "cf-status-secret")
        callbacks.cloudflareStatusResult = expectedStatus

        val response = callbacks.handler(secrets).handle(ManagementApiOperation.CloudflareStatus)

        assertSameResponse(ManagementApiReadOnlyResponses.cloudflareStatus(expectedStatus, secrets), response)
        callbacks.assertCalls("cloudflareStatus")
    }

    @Test
    fun `cloudflare start uses only cloudflare start callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            CloudflareTunnelTransitionResult(
                disposition = CloudflareTunnelTransitionDisposition.Accepted,
                status = CloudflareTunnelStatus.starting(),
            )
        val secrets = LogRedactionSecrets(cloudflareTunnelToken = "cf-start-secret")
        callbacks.cloudflareStartResult = expectedResult

        val response = callbacks.handler(secrets).handle(ManagementApiOperation.CloudflareStart)

        assertSameResponse(ManagementApiCloudflareActionResponses.transition(expectedResult, secrets), response)
        callbacks.assertCalls("cloudflareStart")
    }

    @Test
    fun `cloudflare stop uses only cloudflare stop callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            CloudflareTunnelTransitionResult(
                disposition = CloudflareTunnelTransitionDisposition.Duplicate,
                status = CloudflareTunnelStatus.stopped(),
            )
        callbacks.cloudflareStopResult = expectedResult

        val response = callbacks.handler().handle(ManagementApiOperation.CloudflareStop)

        assertSameResponse(
            ManagementApiCloudflareActionResponses.transition(expectedResult, LogRedactionSecrets()),
            response,
        )
        callbacks.assertCalls("cloudflareStop")
    }

    @Test
    fun `cloudflare reconnect uses only cloudflare reconnect callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            CloudflareTunnelTransitionResult(
                disposition = CloudflareTunnelTransitionDisposition.Accepted,
                status = CloudflareTunnelStatus.connected(),
            )
        callbacks.cloudflareReconnectResult = expectedResult

        val response = callbacks.handler().handle(ManagementApiOperation.CloudflareReconnect)

        assertSameResponse(
            ManagementApiCloudflareActionResponses.transition(expectedResult, LogRedactionSecrets()),
            response,
        )
        callbacks.assertCalls("cloudflareReconnect")
    }

    @Test
    fun `rotate mobile data uses only mobile data rotation callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(state = RotationState.CheckingCooldown, operation = RotationOperation.MobileData),
            )
        callbacks.rotateMobileDataResult = expectedResult

        val response = callbacks.handler().handle(ManagementApiOperation.RotateMobileData)

        assertSameResponse(ManagementApiRotationActionResponses.transition(expectedResult), response)
        callbacks.assertCalls("rotateMobileData")
    }

    @Test
    fun `rotate airplane mode uses only airplane mode rotation callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(state = RotationState.CheckingCooldown, operation = RotationOperation.AirplaneMode),
            )
        callbacks.rotateAirplaneModeResult = expectedResult

        val response = callbacks.handler().handle(ManagementApiOperation.RotateAirplaneMode)

        assertSameResponse(ManagementApiRotationActionResponses.transition(expectedResult), response)
        callbacks.assertCalls("rotateAirplaneMode")
    }

    @Test
    fun `service stop uses only service stop callback and action renderer`() {
        val callbacks = RecordingCallbacks()
        val expectedResult =
            ProxyServiceStopTransitionResult(
                disposition = ProxyServiceStopTransitionDisposition.Accepted,
                status =
                    ProxyServiceStatus(
                        state = ProxyServiceState.Stopping,
                        metrics = ProxyTrafficMetrics(activeConnections = 1, totalConnections = 3),
                    ),
            )
        callbacks.serviceStopResult = expectedResult

        val response = callbacks.handler().handle(ManagementApiOperation.ServiceStop)

        assertSameResponse(ManagementApiServiceStopActionResponses.transition(expectedResult), response)
        callbacks.assertCalls("serviceStop")
    }

    @Test
    fun `handler redacts configured secret in cloudflare status response`() {
        val callbacks = RecordingCallbacks()
        callbacks.cloudflareStatusResult = CloudflareTunnelStatus.failed("failed with cloudflare-secret in log")

        val response =
            callbacks
                .handler(LogRedactionSecrets(cloudflareTunnelToken = "cloudflare-secret"))
                .handle(ManagementApiOperation.CloudflareStatus)

        assertEquals(
            """{"cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"failed with [REDACTED] in log"}}""",
            response.body,
        )
        assertFalse(response.body.contains("cloudflare-secret"))
        callbacks.assertCalls("cloudflareStatus")
    }

    @Test
    fun `handler redacts configured secret in cloudflare start response`() {
        val callbacks = RecordingCallbacks()
        callbacks.cloudflareStartResult =
            CloudflareTunnelTransitionResult(
                disposition = CloudflareTunnelTransitionDisposition.Duplicate,
                status = CloudflareTunnelStatus.failed("edge rejected cloudflare-secret"),
            )

        val response =
            callbacks
                .handler(LogRedactionSecrets(cloudflareTunnelToken = "cloudflare-secret"))
                .handle(ManagementApiOperation.CloudflareStart)

        assertEquals(
            """{"accepted":false,"disposition":"duplicate","cloudflare":{"state":"failed","remoteManagementAvailable":false,"failureReason":"edge rejected [REDACTED]"}}""",
            response.body,
        )
        assertFalse(response.body.contains("cloudflare-secret"))
        callbacks.assertCalls("cloudflareStart")
    }

    private class RecordingCallbacks {
        private val calls = mutableListOf<String>()

        var healthStatusResult: ProxyServiceStatus = stoppedStatus()
        var statusResult: ProxyServiceStatus = stoppedStatus()
        var networksResult: List<NetworkDescriptor> = emptyList()
        var publicIpResult: String? = null
        var rootOperationsEnabledResult: Boolean = false
        var cloudflareStatusResult: CloudflareTunnelStatus = CloudflareTunnelStatus.disabled()
        var cloudflareStartResult: CloudflareTunnelTransitionResult =
            CloudflareTunnelTransitionResult(
                CloudflareTunnelTransitionDisposition.Ignored,
                CloudflareTunnelStatus.disabled(),
            )
        var cloudflareStopResult: CloudflareTunnelTransitionResult =
            CloudflareTunnelTransitionResult(
                CloudflareTunnelTransitionDisposition.Ignored,
                CloudflareTunnelStatus.disabled(),
            )
        var cloudflareReconnectResult: CloudflareTunnelTransitionResult =
            CloudflareTunnelTransitionResult(
                CloudflareTunnelTransitionDisposition.Ignored,
                CloudflareTunnelStatus.disabled(),
            )
        var rotateMobileDataResult: RotationTransitionResult =
            RotationTransitionResult(
                RotationTransitionDisposition.Ignored,
                RotationStatus.idle(),
            )
        var rotateAirplaneModeResult: RotationTransitionResult =
            RotationTransitionResult(
                RotationTransitionDisposition.Ignored,
                RotationStatus.idle(),
            )
        var rotationStatusResult: RotationStatus = RotationStatus.idle()
        var serviceStopResult: ProxyServiceStopTransitionResult =
            ProxyServiceStopTransitionResult(
                ProxyServiceStopTransitionDisposition.Ignored,
                stoppedStatus(),
            )

        fun handler(secrets: LogRedactionSecrets = LogRedactionSecrets()): ManagementApiStateHandler = ManagementApiStateHandler(
            callbacks =
                ManagementApiCallbacks(
                    healthStatus = { record("healthStatus", healthStatusResult) },
                    status = { record("status", statusResult) },
                    networks = { record("networks", networksResult) },
                    publicIp = { record("publicIp", publicIpResult) },
                    cloudflareStatus = { record("cloudflareStatus", cloudflareStatusResult) },
                    cloudflareStart = { record("cloudflareStart", cloudflareStartResult) },
                    cloudflareStop = { record("cloudflareStop", cloudflareStopResult) },
                    cloudflareReconnect = { record("cloudflareReconnect", cloudflareReconnectResult) },
                    rotateMobileData = { record("rotateMobileData", rotateMobileDataResult) },
                    rotateAirplaneMode = { record("rotateAirplaneMode", rotateAirplaneModeResult) },
                    rotationStatus = { record("rotationStatus", rotationStatusResult) },
                    serviceStop = { record("serviceStop", serviceStopResult) },
                    rootOperationsEnabled = { record("rootOperationsEnabled", rootOperationsEnabledResult) },
                ),
            secrets = secrets,
        )

        fun assertCalls(vararg expected: String) {
            assertEquals(expected.toList(), calls)
        }

        private fun <T> record(
            name: String,
            result: T,
        ): T {
            calls += name
            return result
        }
    }
}

private fun assertSameResponse(
    expected: ManagementApiResponse,
    actual: ManagementApiResponse,
) {
    assertEquals(expected.statusCode, actual.statusCode)
    assertEquals(expected.reasonPhrase, actual.reasonPhrase)
    assertEquals(expected.headers, actual.headers)
    assertEquals(expected.body, actual.body)
}

private fun stoppedStatus(): ProxyServiceStatus = ProxyServiceStatus.stopped()

private fun runningStatus(): ProxyServiceStatus = ProxyServiceStatus.running(
    listenHost = "0.0.0.0",
    listenPort = 8080,
    configuredRoute = RouteTarget.Automatic,
    boundRoute = null,
    publicIp = "198.51.100.10",
    hasHighSecurityRisk = false,
    cloudflare = CloudflareTunnelStatus.failed("failed with status-secret"),
)
