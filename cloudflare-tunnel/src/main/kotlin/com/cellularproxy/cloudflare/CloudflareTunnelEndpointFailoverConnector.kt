package com.cellularproxy.cloudflare

import java.util.concurrent.CancellationException

class CloudflareTunnelEndpointFailoverConnector(
    private val dialer: CloudflareTunnelEndpointDialer,
    private val endpointPolicy: CloudflareTunnelEdgeEndpointPolicy = CloudflareTunnelEdgeEndpointPolicy,
) : CloudflareTunnelEdgeConnector {
    override fun connect(credentials: CloudflareTunnelCredentials): CloudflareTunnelEdgeConnectionResult {
        var lastFailure: CloudflareTunnelEdgeConnectionResult.Failed? = null
        endpointPolicy.resolve(credentials).forEach { endpoint ->
            when (val result = dialEndpoint(endpoint, credentials)) {
                is CloudflareTunnelEdgeConnectionResult.Connected -> return result
                is CloudflareTunnelEdgeConnectionResult.Failed -> lastFailure = result
            }
        }
        return lastFailure ?: CloudflareTunnelEdgeConnectionResult.Failed(
            CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable,
        )
    }

    private fun dialEndpoint(
        endpoint: CloudflareTunnelEdgeEndpoint,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelEdgeConnectionResult = try {
        dialer.connect(endpoint, credentials)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        CloudflareTunnelEdgeConnectionResult.Failed(CloudflareTunnelEdgeConnectionFailure.EdgeUnavailable)
    }
}

fun interface CloudflareTunnelEndpointDialer {
    fun connect(
        endpoint: CloudflareTunnelEdgeEndpoint,
        credentials: CloudflareTunnelCredentials,
    ): CloudflareTunnelEdgeConnectionResult
}
