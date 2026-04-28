package com.cellularproxy.app.service

import com.cellularproxy.app.config.SensitiveConfig
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalManagementApiActionDispatcherTest {
    @Test
    fun `dispatch posts authenticated Cloudflare start request to loopback management endpoint`() {
        val requests = mutableListOf<LocalManagementApiActionRequest>()
        val dispatcher =
            LocalManagementApiActionDispatcher(
                transport = { request ->
                    requests += request
                    LocalManagementApiActionResponse(statusCode = 202)
                },
            )

        dispatcher.dispatch(
            action = LocalManagementApiAction.CloudflareStart,
            config =
                AppConfig.default().copy(
                    proxy = ProxyConfig(listenHost = "0.0.0.0", listenPort = 9090),
                    cloudflare = CloudflareConfig(enabled = true, tunnelTokenPresent = true),
                ),
            sensitiveConfig = sensitiveConfig(),
        )

        assertEquals(
            listOf(
                LocalManagementApiActionRequest(
                    method = "POST",
                    url = "http://127.0.0.1:9090/api/cloudflare/start",
                    bearerToken = "management-token",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `dispatch maps rotation actions to management endpoints on explicit loopback host`() {
        val requests = mutableListOf<LocalManagementApiActionRequest>()
        val dispatcher =
            LocalManagementApiActionDispatcher(
                transport = { request ->
                    requests += request
                    LocalManagementApiActionResponse(statusCode = 409)
                },
            )
        val config =
            AppConfig.default().copy(
                proxy = ProxyConfig(listenHost = "127.0.0.1", listenPort = 8181),
            )

        dispatcher.dispatch(
            action = LocalManagementApiAction.RotateMobileData,
            config = config,
            sensitiveConfig = sensitiveConfig(),
        )
        dispatcher.dispatch(
            action = LocalManagementApiAction.RotateAirplaneMode,
            config = config,
            sensitiveConfig = sensitiveConfig(),
        )

        assertEquals(
            listOf(
                LocalManagementApiActionRequest(
                    method = "POST",
                    url = "http://127.0.0.1:8181/api/rotate/mobile-data",
                    bearerToken = "management-token",
                ),
                LocalManagementApiActionRequest(
                    method = "POST",
                    url = "http://127.0.0.1:8181/api/rotate/airplane-mode",
                    bearerToken = "management-token",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `response treats only 2xx management action statuses as successful`() {
        assertEquals(true, LocalManagementApiActionResponse(statusCode = 202).isSuccessful)
        assertEquals(false, LocalManagementApiActionResponse(statusCode = 409).isSuccessful)
        assertEquals(false, LocalManagementApiActionResponse(statusCode = 503).isSuccessful)
    }

    @Test
    fun `cloudflare stop maps to management stop endpoint`() {
        val requests = mutableListOf<LocalManagementApiActionRequest>()
        val dispatcher =
            LocalManagementApiActionDispatcher(
                transport = { request ->
                    requests += request
                    LocalManagementApiActionResponse(statusCode = 202)
                },
            )

        dispatcher.dispatch(
            action = LocalManagementApiAction.CloudflareStop,
            config = AppConfig.default(),
            sensitiveConfig = sensitiveConfig(),
        )

        assertEquals(
            listOf(
                LocalManagementApiActionRequest(
                    method = "POST",
                    url = "http://127.0.0.1:8080/api/cloudflare/stop",
                    bearerToken = "management-token",
                ),
            ),
            requests,
        )
    }
}

private fun sensitiveConfig(): SensitiveConfig = SensitiveConfig(
    proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
    managementApiToken = "management-token",
    cloudflareTunnelToken = "cloudflare-token",
)
