package com.cellularproxy.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.cellularproxy.network.BoundNetworkSocketConnector
import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.shared.network.NetworkDescriptor
import com.cellularproxy.shared.network.NetworkSnapshot
import com.cellularproxy.shared.network.NetworkTransport
import com.cellularproxy.shared.network.toNetworkDescriptorOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class AndroidNetworkRouteMonitor private constructor(
    private val observationSource: AndroidNetworkObservationSource,
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        observationSource.start()
    }

    fun observedNetworks(): List<NetworkDescriptor> =
        observationSource
            .observations()
            .mapNotNull(AndroidNetworkObservation::toNetworkDescriptorOrNull)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        observationSource.stop()
    }

    companion object {
        fun create(context: Context): AndroidNetworkRouteMonitor = create(context.getSystemService(ConnectivityManager::class.java))

        fun create(connectivityManager: ConnectivityManager): AndroidNetworkRouteMonitor =
            AndroidNetworkRouteMonitor(
                ConnectivityManagerNetworkObservationSource(connectivityManager),
            )

        internal fun forTesting(observations: () -> List<AndroidNetworkObservation>): AndroidNetworkRouteMonitor =
            AndroidNetworkRouteMonitor(
                object : AndroidNetworkObservationSource {
                    override fun observations(): List<AndroidNetworkObservation> = observations()
                },
            )

        internal fun forTesting(observationSource: AndroidNetworkObservationSource): AndroidNetworkRouteMonitor = AndroidNetworkRouteMonitor(
            observationSource,
        )
    }
}

data class AndroidNetworkObservation(
    val handle: Long,
    val transports: Set<AndroidNetworkTransport>,
    val hasInternet: Boolean,
    val isSuspended: Boolean,
) {
    init {
        require(handle >= 0) { "Android network handle must not be negative" }
    }
}

enum class AndroidNetworkTransport {
    WiFi,
    Cellular,
    Vpn,
    Other,
}

internal interface AndroidNetworkObservationSource {
    fun start() = Unit

    fun observations(): List<AndroidNetworkObservation>

    fun stop() = Unit
}

class AndroidBoundNetworkSocketConnector private constructor(
    private val operations: AndroidBoundSocketOperations,
) : BoundNetworkSocketConnector {
    override suspend fun connect(
        network: NetworkDescriptor,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): BoundSocketConnectResult {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in VALID_PORT_RANGE) { "Port must be in range 1..65535" }
        require(timeoutMillis in 1..Int.MAX_VALUE.toLong()) {
            "Timeout must be positive and fit in Android socket timeout range"
        }

        if (!network.isAvailable) {
            return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
        }

        val handle =
            network.androidHandleOrNull()
                ?: return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
        if (handle !in operations.currentNetworkHandles()) {
            return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
        }

        val addresses =
            try {
                operations.resolveAll(handle = handle, host = host)
            } catch (_: UnknownHostException) {
                return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.DnsResolutionFailed)
            } catch (_: SecurityException) {
                return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.DnsResolutionFailed)
            }
        if (addresses.isEmpty()) {
            return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.DnsResolutionFailed)
        }

        var timedOut = false
        for (address in addresses) {
            try {
                return BoundSocketConnectResult.Connected(
                    socket =
                        operations.connect(
                            handle = handle,
                            address = address,
                            port = port,
                            timeoutMillis = timeoutMillis.toInt(),
                        ),
                    network = network,
                )
            } catch (_: SocketTimeoutException) {
                timedOut = true
            } catch (_: SelectedAndroidNetworkUnavailableException) {
                return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable)
            } catch (_: IOException) {
                // Try the next resolved address before reporting connection failure.
            } catch (_: SecurityException) {
                return BoundSocketConnectResult.Failed(BoundSocketConnectFailure.ConnectionFailed)
            }
        }

        return BoundSocketConnectResult.Failed(
            if (timedOut) {
                BoundSocketConnectFailure.ConnectionTimedOut
            } else {
                BoundSocketConnectFailure.ConnectionFailed
            },
        )
    }

    companion object {
        fun create(context: Context): AndroidBoundNetworkSocketConnector =
            create(
                context.getSystemService(ConnectivityManager::class.java),
            )

        fun create(connectivityManager: ConnectivityManager): AndroidBoundNetworkSocketConnector =
            AndroidBoundNetworkSocketConnector(
                ConnectivityManagerBoundSocketOperations(connectivityManager),
            )

        internal fun forTesting(operations: AndroidBoundSocketOperations): AndroidBoundNetworkSocketConnector = AndroidBoundNetworkSocketConnector(
            operations,
        )
    }
}

internal interface AndroidBoundSocketOperations {
    fun currentNetworkHandles(): List<Long>

    fun resolveAll(
        handle: Long,
        host: String,
    ): List<InetAddress>

    fun connect(
        handle: Long,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ): Socket
}

private class ConnectivityManagerNetworkObservationSource(
    private val connectivityManager: ConnectivityManager,
) : AndroidNetworkObservationSource {
    private val lock = Any()
    private val observationsByHandle = linkedMapOf<Long, AndroidNetworkObservation>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start() {
        synchronized(lock) {
            if (callback != null) {
                return
            }
            refreshAllLocked()
            val networkCallback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        update(network)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        update(network, networkCapabilities)
                    }

                    override fun onLost(network: Network) {
                        synchronized(lock) {
                            observationsByHandle.remove(network.networkHandle)
                        }
                    }
                }
            connectivityManager.registerNetworkCallback(routeDiscoveryNetworkRequest(), networkCallback)
            callback = networkCallback
        }
    }

    @Suppress("DEPRECATION")
    override fun observations(): List<AndroidNetworkObservation> =
        synchronized(lock) {
            observationsByHandle.values.toList()
        }

    override fun stop() {
        val callbackToUnregister =
            synchronized(lock) {
                callback.also {
                    callback = null
                    observationsByHandle.clear()
                }
            } ?: return

        connectivityManager.unregisterNetworkCallback(callbackToUnregister)
    }

    private fun update(network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        update(network, capabilities)
    }

    private fun update(
        network: Network,
        capabilities: NetworkCapabilities,
    ) {
        synchronized(lock) {
            observationsByHandle[network.networkHandle] = capabilities.toObservation(network.networkHandle)
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshAllLocked() {
        observationsByHandle.clear()
        connectivityManager.allNetworks.forEach { network ->
            connectivityManager
                .getNetworkCapabilities(network)
                ?.let { capabilities ->
                    observationsByHandle[network.networkHandle] = capabilities.toObservation(network.networkHandle)
                }
        }
    }
}

private class ConnectivityManagerBoundSocketOperations(
    private val connectivityManager: ConnectivityManager,
) : AndroidBoundSocketOperations {
    @Suppress("DEPRECATION")
    override fun currentNetworkHandles(): List<Long> = connectivityManager.allNetworks.map(Network::getNetworkHandle)

    override fun resolveAll(
        handle: Long,
        host: String,
    ): List<InetAddress> =
        networkForHandle(handle)?.getAllByName(host)?.toList()
            ?: emptyList()

    override fun connect(
        handle: Long,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ): Socket {
        val network =
            networkForHandle(handle)
                ?: throw SelectedAndroidNetworkUnavailableException()
        val socket = Socket()
        try {
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            return socket
        } catch (throwable: Throwable) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Preserve the original connect failure.
            }
            throw throwable
        }
    }

    @Suppress("DEPRECATION")
    private fun networkForHandle(handle: Long): Network? = connectivityManager.allNetworks.firstOrNull { it.networkHandle == handle }
}

class SelectedAndroidNetworkUnavailableException internal constructor() : IOException("Selected Android network is no longer available")

private fun NetworkCapabilities.toObservation(handle: Long): AndroidNetworkObservation =
    AndroidNetworkObservation(
        handle = handle,
        transports =
            buildSet {
                if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    add(AndroidNetworkTransport.WiFi)
                }
                if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    add(AndroidNetworkTransport.Cellular)
                }
                if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    add(AndroidNetworkTransport.Vpn)
                }
                if (hasAnyOtherTransport()) {
                    add(AndroidNetworkTransport.Other)
                }
                if (isEmpty()) {
                    add(AndroidNetworkTransport.Other)
                }
            },
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isSuspended = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
    )

private fun NetworkCapabilities.hasAnyOtherTransport(): Boolean =
    otherAndroidTransportProbesForSdkInt(Build.VERSION.SDK_INT)
        .any { probe -> hasTransport(probe.transport) }

private fun AndroidNetworkObservation.toNetworkDescriptorOrNull(): NetworkDescriptor? =
    NetworkSnapshot(
        id = androidNetworkDescriptorId(handle),
        displayName = transports.displayName(),
        isAvailable = hasInternet && !isSuspended,
        transports = transports.mapTo(mutableSetOf(), AndroidNetworkTransport::toSharedTransport),
    ).toNetworkDescriptorOrNull()

private fun Set<AndroidNetworkTransport>.displayName(): String =
    when {
        AndroidNetworkTransport.Vpn in this -> "VPN"
        this == setOf(AndroidNetworkTransport.WiFi) -> "Wi-Fi"
        this == setOf(AndroidNetworkTransport.Cellular) -> "Cellular"
        else -> "Android network"
    }

private fun AndroidNetworkTransport.toSharedTransport(): NetworkTransport =
    when (this) {
        AndroidNetworkTransport.WiFi -> NetworkTransport.WiFi
        AndroidNetworkTransport.Cellular -> NetworkTransport.Cellular
        AndroidNetworkTransport.Vpn -> NetworkTransport.Vpn
        AndroidNetworkTransport.Other -> NetworkTransport.Other
    }

private fun NetworkDescriptor.androidHandleOrNull(): Long? =
    id
        .removePrefix(ANDROID_NETWORK_ID_PREFIX)
        .takeIf { it != id && it.isNotBlank() }
        ?.toLongOrNull()

private fun androidNetworkDescriptorId(handle: Long): String = "$ANDROID_NETWORK_ID_PREFIX$handle"

private val VALID_PORT_RANGE = 1..65_535

internal data class AndroidTransportProbe(
    val name: String,
    val transport: Int,
    val minSdk: Int,
)

internal fun otherAndroidTransportProbesForSdkInt(sdkInt: Int): List<AndroidTransportProbe> =
    OTHER_ANDROID_TRANSPORT_PROBES
        .filter { probe -> sdkInt >= probe.minSdk }
        .sortedBy(AndroidTransportProbe::name)

private val OTHER_ANDROID_TRANSPORT_PROBES =
    listOf(
        AndroidTransportProbe("BLUETOOTH", NetworkCapabilities.TRANSPORT_BLUETOOTH, minSdk = 21),
        AndroidTransportProbe("ETHERNET", NetworkCapabilities.TRANSPORT_ETHERNET, minSdk = 21),
        AndroidTransportProbe("LOWPAN", NetworkCapabilities.TRANSPORT_LOWPAN, minSdk = 27),
        AndroidTransportProbe("USB", NetworkCapabilities.TRANSPORT_USB, minSdk = 31),
        AndroidTransportProbe("WIFI_AWARE", NetworkCapabilities.TRANSPORT_WIFI_AWARE, minSdk = 26),
        AndroidTransportProbe("THREAD", NetworkCapabilities.TRANSPORT_THREAD, minSdk = 34),
        AndroidTransportProbe("SATELLITE", NetworkCapabilities.TRANSPORT_SATELLITE, minSdk = 35),
    )

private fun routeDiscoveryNetworkRequest(): NetworkRequest =
    NetworkRequest
        .Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

private const val ANDROID_NETWORK_ID_PREFIX = "android-network:"
