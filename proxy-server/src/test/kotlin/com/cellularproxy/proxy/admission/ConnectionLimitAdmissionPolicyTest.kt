package com.cellularproxy.proxy.admission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConnectionLimitAdmissionPolicyTest {
    @Test
    fun `accepts connections below the configured maximum`() {
        val config = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 3)

        val decision = ConnectionLimitAdmissionPolicy.evaluate(
            config = config,
            activeConnections = 2,
        )

        val accepted = assertIs<ConnectionLimitAdmissionDecision.Accepted>(decision)
        assertEquals(3, accepted.activeConnectionsAfterAdmission)
    }

    @Test
    fun `rejects connections at the configured maximum`() {
        val config = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 3)

        val decision = ConnectionLimitAdmissionPolicy.evaluate(
            config = config,
            activeConnections = 3,
        )

        assertEquals(
            ConnectionLimitAdmissionDecision.Rejected(
                ConnectionLimitAdmissionRejectionReason.MaximumConcurrentConnectionsReached(
                    activeConnections = 3,
                    maxConcurrentConnections = 3,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects connections above the configured maximum`() {
        val config = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 3)

        val decision = ConnectionLimitAdmissionPolicy.evaluate(
            config = config,
            activeConnections = 4,
        )

        assertEquals(
            ConnectionLimitAdmissionDecision.Rejected(
                ConnectionLimitAdmissionRejectionReason.MaximumConcurrentConnectionsReached(
                    activeConnections = 4,
                    maxConcurrentConnections = 3,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `rejects invalid connection limit configuration`() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionLimitAdmissionConfig(maxConcurrentConnections = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionLimitAdmissionConfig(maxConcurrentConnections = -1)
        }
    }

    @Test
    fun `rejects invalid active connection counts`() {
        val config = ConnectionLimitAdmissionConfig(maxConcurrentConnections = 1)

        assertFailsWith<IllegalArgumentException> {
            ConnectionLimitAdmissionPolicy.evaluate(
                config = config,
                activeConnections = -1,
            )
        }
    }
}
