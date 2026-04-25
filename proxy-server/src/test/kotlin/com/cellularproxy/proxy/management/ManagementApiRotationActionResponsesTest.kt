package com.cellularproxy.proxy.management

import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagementApiRotationActionResponsesTest {
    @Test
    fun `accepted rotation transition renders accepted response`() {
        val response = ManagementApiRotationActionResponses.transition(
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(
                    state = RotationState.CheckingCooldown,
                    operation = RotationOperation.MobileData,
                ),
            ),
        )

        assertEquals(202, response.statusCode)
        assertEquals(
            """{"accepted":true,"disposition":"accepted","rotation":{"state":"checking_cooldown","operation":"mobile_data","oldPublicIp":null,"newPublicIp":null,"publicIpChanged":null,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `duplicate rotation transition renders conflict response`() {
        val response = ManagementApiRotationActionResponses.transition(
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Duplicate,
                status = RotationStatus(
                    state = RotationState.WaitingForNetworkReturn,
                    operation = RotationOperation.AirplaneMode,
                    oldPublicIp = "198.51.100.10",
                ),
            ),
        )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"duplicate","rotation":{"state":"waiting_for_network_return","operation":"airplane_mode","oldPublicIp":"198.51.100.10","newPublicIp":null,"publicIpChanged":null,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `ignored rotation transition renders conflict response`() {
        val response = ManagementApiRotationActionResponses.transition(
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Ignored,
                status = RotationStatus.idle(),
            ),
        )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"disposition":"ignored","rotation":{"state":"idle","operation":null,"oldPublicIp":null,"newPublicIp":null,"publicIpChanged":null,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `completed rotation transition renders public ip change result`() {
        val response = ManagementApiRotationActionResponses.transition(
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(
                    state = RotationState.Completed,
                    operation = RotationOperation.MobileData,
                    oldPublicIp = "198.51.100.10",
                    newPublicIp = "203.0.113.25",
                    publicIpChanged = true,
                ),
            ),
        )

        assertEquals(
            """{"accepted":true,"disposition":"accepted","rotation":{"state":"completed","operation":"mobile_data","oldPublicIp":"198.51.100.10","newPublicIp":"203.0.113.25","publicIpChanged":true,"failureReason":null}}""",
            response.body,
        )
    }

    @Test
    fun `failed rotation transition renders structured failure reason`() {
        val response = ManagementApiRotationActionResponses.transition(
            RotationTransitionResult(
                disposition = RotationTransitionDisposition.Accepted,
                status = RotationStatus(
                    state = RotationState.Failed,
                    operation = RotationOperation.AirplaneMode,
                    failureReason = RotationFailureReason.RootCommandTimedOut,
                ),
            ),
        )

        assertEquals(
            """{"accepted":true,"disposition":"accepted","rotation":{"state":"failed","operation":"airplane_mode","oldPublicIp":null,"newPublicIp":null,"publicIpChanged":null,"failureReason":"root_command_timed_out"}}""",
            response.body,
        )
    }
}
