package com.cellularproxy.app.service

import android.security.NetworkSecurityPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityPolicyInstrumentationTest {
    @Test
    fun loopbackCleartextTrafficIsPermittedForLocalManagementApi() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
    }
}
