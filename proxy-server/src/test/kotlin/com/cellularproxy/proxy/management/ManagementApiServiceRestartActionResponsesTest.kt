package com.cellularproxy.proxy.management

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagementApiServiceRestartActionResponsesTest {
    @Test
    fun `accepted service restart renders scheduled response`() {
        val response =
            ManagementApiServiceRestartActionResponses.transition(
                ManagementApiServiceRestartResult.accepted(packageName = "com.cellularproxy"),
            )

        assertEquals(202, response.statusCode)
        assertEquals("""{"accepted":true,"restart":{"packageName":"com.cellularproxy","failureReason":null}}""", response.body)
    }

    @Test
    fun `rejected service restart renders failure reason`() {
        val response =
            ManagementApiServiceRestartActionResponses.transition(
                ManagementApiServiceRestartResult.rejected(
                    failureReason = ManagementApiServiceRestartFailureReason.RootOperationsDisabled,
                ),
            )

        assertEquals(409, response.statusCode)
        assertEquals(
            """{"accepted":false,"restart":{"packageName":null,"failureReason":"root_operations_disabled"}}""",
            response.body,
        )
    }
}
