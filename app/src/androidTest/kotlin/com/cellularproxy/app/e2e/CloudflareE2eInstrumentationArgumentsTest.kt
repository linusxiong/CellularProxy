package com.cellularproxy.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
}
