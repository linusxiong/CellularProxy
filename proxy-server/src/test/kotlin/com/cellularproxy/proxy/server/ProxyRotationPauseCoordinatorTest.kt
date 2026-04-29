package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.admission.ConnectionLimitAdmissionConfig
import com.cellularproxy.proxy.admission.ProxyRequestAdmissionConfig
import com.cellularproxy.proxy.ingress.ProxyIngressPreflightConfig
import com.cellularproxy.shared.proxy.ProxyAuthenticationConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import com.cellularproxy.shared.rotation.RotationControlPlane
import com.cellularproxy.shared.rotation.RotationEvent
import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.TerminalRotationTimestampObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyRotationPauseCoordinatorTest {
    @Test
    fun `pauses proxy requests and advances control plane to draining connections`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.PausingNewRequests,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val pauseController = LockRecordingPauseActions(controlPlane)
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        val result = coordinator.advance(nowElapsedMillis = 10_000)

        assertEquals(RotationEvent.NewRequestsPaused, (result as ProxyRotationPauseAdvanceResult.Applied).event)
        assertEquals(RotationTransitionDisposition.Accepted, result.progress.transition.disposition)
        assertEquals(RotationState.DrainingConnections, controlPlane.currentStatus.state)
        assertTrue(pauseController.proxyRequestsPaused)
        assertEquals(true, pauseController.pauseHeldControlPlaneLock)
    }

    @Test
    fun `resumes proxy requests and records terminal completion timestamp`() {
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.ResumingProxyRequests,
                        operation = RotationOperation.AirplaneMode,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.25",
                        publicIpChanged = true,
                    ),
            )
        val pauseController =
            LockRecordingPauseActions(
                controlPlane = controlPlane,
                initiallyPaused = true,
            )
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        val result = coordinator.advance(nowElapsedMillis = 20_000)

        assertEquals(RotationEvent.ProxyRequestsResumed, (result as ProxyRotationPauseAdvanceResult.Applied).event)
        assertEquals(RotationTransitionDisposition.Accepted, result.progress.transition.disposition)
        assertEquals(RotationState.Completed, controlPlane.currentStatus.state)
        assertEquals(20_000, controlPlane.lastTerminalElapsedMillis)
        assertEquals(
            TerminalRotationTimestampObservation.Recorded(20_000),
            result.progress.terminalTimestampObservation,
        )
        assertFalse(pauseController.proxyRequestsPaused)
        assertEquals(true, pauseController.resumeHeldControlPlaneLock)
    }

    @Test
    fun `resumes proxy requests and records terminal failure timestamp`() {
        val pauseController =
            ProxyRotationRequestPauseController(
                ingressConfig().copy(proxyRequestsPaused = true),
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.ResumingProxyRequests,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        failureReason = RotationFailureReason.RootCommandFailed,
                    ),
            )
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        val result = coordinator.advance(nowElapsedMillis = 30_000)

        assertEquals(RotationEvent.ProxyRequestsResumed, (result as ProxyRotationPauseAdvanceResult.Applied).event)
        assertEquals(RotationState.Failed, controlPlane.currentStatus.state)
        assertEquals(30_000, controlPlane.lastTerminalElapsedMillis)
        assertFalse(pauseController.proxyRequestsPaused)
    }

    @Test
    fun `does not change runtime pause state when control plane is not waiting for pause or resume`() {
        val pauseController = ProxyRotationRequestPauseController(ingressConfig())
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.DrainingConnections,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        val result = coordinator.advance(nowElapsedMillis = 10_000)

        assertTrue(result is ProxyRotationPauseAdvanceResult.NoAction)
        assertEquals(RotationState.DrainingConnections, controlPlane.currentStatus.state)
        assertFalse(pauseController.proxyRequestsPaused)
    }

    @Test
    fun `rejects negative observation time before changing runtime pause state`() {
        val pauseController = ProxyRotationRequestPauseController(ingressConfig())
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.PausingNewRequests,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                    ),
            )
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        assertFailsWith<IllegalArgumentException> {
            coordinator.advance(nowElapsedMillis = -1)
        }

        assertEquals(RotationState.PausingNewRequests, controlPlane.currentStatus.state)
        assertFalse(pauseController.proxyRequestsPaused)
    }

    @Test
    fun `rejects negative observation time before changing runtime resume state`() {
        val pauseController =
            ProxyRotationRequestPauseController(
                ingressConfig().copy(proxyRequestsPaused = true),
            )
        val controlPlane =
            RotationControlPlane(
                initialStatus =
                    RotationStatus(
                        state = RotationState.ResumingProxyRequests,
                        operation = RotationOperation.MobileData,
                        oldPublicIp = "198.51.100.10",
                        newPublicIp = "203.0.113.25",
                        publicIpChanged = true,
                    ),
            )
        val coordinator =
            ProxyRotationPauseCoordinator(
                pauseController = pauseController,
                controlPlane = controlPlane,
            )

        assertFailsWith<IllegalArgumentException> {
            coordinator.advance(nowElapsedMillis = -1)
        }

        assertEquals(RotationState.ResumingProxyRequests, controlPlane.currentStatus.state)
        assertTrue(pauseController.proxyRequestsPaused)
    }

    private fun ingressConfig(): ProxyIngressPreflightConfig = ProxyIngressPreflightConfig(
        connectionLimit = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 4),
        requestAdmission =
            ProxyRequestAdmissionConfig(
                proxyAuthentication =
                    ProxyAuthenticationConfig(
                        authEnabled = false,
                        credential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                    ),
                managementApiToken = "management-token",
            ),
    )

    private class LockRecordingPauseActions(
        private val controlPlane: RotationControlPlane,
        initiallyPaused: Boolean = false,
    ) : ProxyRotationPauseActions {
        private var paused = initiallyPaused

        var pauseHeldControlPlaneLock: Boolean? = null
            private set

        var resumeHeldControlPlaneLock: Boolean? = null
            private set

        val proxyRequestsPaused: Boolean
            get() = paused

        override fun pauseProxyRequests(): RotationEvent.NewRequestsPaused {
            pauseHeldControlPlaneLock = Thread.holdsLock(controlPlane)
            paused = true
            return RotationEvent.NewRequestsPaused
        }

        override fun resumeProxyRequests(): RotationEvent.ProxyRequestsResumed {
            resumeHeldControlPlaneLock = Thread.holdsLock(controlPlane)
            paused = false
            return RotationEvent.ProxyRequestsResumed
        }
    }
}
