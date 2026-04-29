package com.cellularproxy.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class CloudflareE2eInstrumentationArgumentsTest {
    @Test
    fun instrumentationRegistryArgumentsLoadThroughE2eConfigContract() {
        val arguments = InstrumentationRegistry.getArguments()
        val config =
            CloudflareE2eValidationConfig.fromInstrumentationArguments(
                arguments,
            )
        val expected =
            CloudflareE2eValidationConfig.fromInstrumentationArguments(
                CloudflareE2eValidationInstrumentationArguments.all.associateWith(arguments::getString),
            )

        assertEquals(expected, config)
    }

    @Test
    fun explicitCloudflareManagementApiRoundTripValidationHook() {
        val config =
            CloudflareE2eValidationConfig.fromInstrumentationArguments(
                InstrumentationRegistry.getArguments(),
            )
        assumeTrue(config is CloudflareE2eValidationConfig.Ready)
        config as CloudflareE2eValidationConfig.Ready

        val evidence =
            CloudflareE2eValidationController(
                elapsedRealtimeMillis = System::currentTimeMillis,
                validator = CloudflareE2eManagementApiRoundTripValidator()::validate,
            ).run(config)

        requireSuccessfulCloudflareE2eEvidence(evidence)
        assertNotNull(evidence.safeSummary)
        assertFalse(evidence.safeSummary.contains(config.tunnelToken))
        config.managementApiToken?.let { managementApiToken ->
            assertFalse(evidence.safeSummary.contains(managementApiToken))
        }
    }
}
