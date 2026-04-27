package com.cellularproxy.app.network

import com.cellularproxy.network.BoundSocketConnectFailure
import com.cellularproxy.network.BoundSocketConnectResult
import com.cellularproxy.shared.network.NetworkCategory
import com.cellularproxy.shared.network.NetworkDescriptor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidNetworkRouteBindingsTest {
    @Test
    fun `network monitor maintains callback-backed route state`() {
        val source = RecordingAndroidNetworkObservationSource()
        val monitor = AndroidNetworkRouteMonitor.forTesting(source)

        source.publish(
            AndroidNetworkObservation(
                handle = 20L,
                transports = setOf(AndroidNetworkTransport.WiFi),
                hasInternet = true,
                isSuspended = false,
            ),
        )

        assertEquals(
            listOf(
                NetworkDescriptor(
                    id = "android-network:20",
                    category = NetworkCategory.WiFi,
                    displayName = "Wi-Fi",
                    isAvailable = true,
                ),
            ),
            monitor.observedNetworks(),
        )
        assertEquals(1, source.startCount)

        source.lose(20L)

        assertEquals(emptyList(), monitor.observedNetworks())

        monitor.close()
        monitor.close()

        assertEquals(1, source.stopCount)
    }

    @Test
    fun `network monitor maps Android transport observations into route descriptors`() {
        val monitor =
            AndroidNetworkRouteMonitor.forTesting {
                listOf(
                    AndroidNetworkObservation(
                        handle = 10L,
                        transports = setOf(AndroidNetworkTransport.WiFi),
                        hasInternet = true,
                        isSuspended = false,
                    ),
                    AndroidNetworkObservation(
                        handle = 11L,
                        transports = setOf(AndroidNetworkTransport.Cellular),
                        hasInternet = true,
                        isSuspended = true,
                    ),
                    AndroidNetworkObservation(
                        handle = 12L,
                        transports = setOf(AndroidNetworkTransport.Vpn, AndroidNetworkTransport.Other),
                        hasInternet = true,
                        isSuspended = false,
                    ),
                    AndroidNetworkObservation(
                        handle = 13L,
                        transports = setOf(AndroidNetworkTransport.Other),
                        hasInternet = true,
                        isSuspended = false,
                    ),
                )
            }

        assertEquals(
            listOf(
                NetworkDescriptor(
                    id = "android-network:10",
                    category = NetworkCategory.WiFi,
                    displayName = "Wi-Fi",
                    isAvailable = true,
                ),
                NetworkDescriptor(
                    id = "android-network:11",
                    category = NetworkCategory.Cellular,
                    displayName = "Cellular",
                    isAvailable = false,
                ),
                NetworkDescriptor(
                    id = "android-network:12",
                    category = NetworkCategory.Vpn,
                    displayName = "VPN",
                    isAvailable = true,
                ),
            ),
            monitor.observedNetworks(),
        )
    }

    @Test
    fun `other Android transport probing only includes constants known to the runtime SDK`() {
        assertEquals(
            listOf("BLUETOOTH", "ETHERNET", "LOWPAN", "WIFI_AWARE"),
            otherAndroidTransportProbesForSdkInt(29).map(AndroidTransportProbe::name),
        )
        assertEquals(
            listOf("BLUETOOTH", "ETHERNET", "LOWPAN", "USB", "WIFI_AWARE"),
            otherAndroidTransportProbesForSdkInt(31).map(AndroidTransportProbe::name),
        )
        assertEquals(
            listOf("BLUETOOTH", "ETHERNET", "LOWPAN", "THREAD", "USB", "WIFI_AWARE"),
            otherAndroidTransportProbesForSdkInt(34).map(AndroidTransportProbe::name),
        )
        assertEquals(
            listOf("BLUETOOTH", "ETHERNET", "LOWPAN", "SATELLITE", "THREAD", "USB", "WIFI_AWARE"),
            otherAndroidTransportProbesForSdkInt(35).map(AndroidTransportProbe::name),
        )
    }

    @Test
    fun `bound connector reports selected route unavailable when route disappears during connect`() =
        runBlocking {
            val routeLostFailure =
                AndroidBoundNetworkSocketConnector
                    .forTesting(
                        RecordingAndroidBoundSocketOperations(
                            availableHandles = listOf(42L),
                            resolvedAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                            connectFailure = SelectedAndroidNetworkUnavailableException(),
                        ),
                    ).connect(androidCellularNetwork(), "example.test", 443, 1_000L)

            assertEquals(
                BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable),
                routeLostFailure,
            )
        }

    @Test
    fun `bound connector resolves and connects through the descriptor network handle`() =
        runBlocking {
            val server = ServerSocket(0)
            val accepted = mutableListOf<Socket>()
            val acceptThread =
                Thread {
                    accepted += server.accept()
                }.also(Thread::start)
            val operations =
                RecordingAndroidBoundSocketOperations(
                    availableHandles = listOf(42L),
                    resolvedAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                )
            val connector = AndroidBoundNetworkSocketConnector.forTesting(operations)

            try {
                val result =
                    connector.connect(
                        network =
                            NetworkDescriptor(
                                id = "android-network:42",
                                category = NetworkCategory.Cellular,
                                displayName = "Cellular",
                                isAvailable = true,
                            ),
                        host = "example.test",
                        port = server.localPort,
                        timeoutMillis = 1_000L,
                    )

                assertIs<BoundSocketConnectResult.Connected>(result)
                assertEquals(42L, operations.resolvedHandle)
                assertEquals("example.test", operations.resolvedHost)
                assertEquals(42L, operations.connectedHandle)
                assertEquals(server.localPort, operations.connectedPort)
                assertEquals(1_000, operations.connectedTimeoutMillis)
                assertEquals(
                    NetworkDescriptor(
                        id = "android-network:42",
                        category = NetworkCategory.Cellular,
                        displayName = "Cellular",
                        isAvailable = true,
                    ),
                    result.network,
                )
                result.socket.close()
            } finally {
                server.close()
                acceptThread.join(1_000L)
                accepted.forEach(Socket::close)
            }
        }

    @Test
    fun `bound connector fails quickly when descriptor handle is not currently available`() =
        runBlocking {
            val operations = RecordingAndroidBoundSocketOperations(availableHandles = listOf(7L))
            val connector = AndroidBoundNetworkSocketConnector.forTesting(operations)

            val result =
                connector.connect(
                    network =
                        NetworkDescriptor(
                            id = "android-network:8",
                            category = NetworkCategory.WiFi,
                            displayName = "Wi-Fi",
                            isAvailable = true,
                        ),
                    host = "example.test",
                    port = 443,
                    timeoutMillis = 1_000L,
                )

            assertEquals(
                BoundSocketConnectResult.Failed(BoundSocketConnectFailure.SelectedRouteUnavailable),
                result,
            )
            assertEquals(null, operations.resolvedHandle)
        }

    @Test
    fun `bound connector maps DNS and connect failures without leaking target details`() =
        runBlocking {
            val dnsFailure =
                AndroidBoundNetworkSocketConnector
                    .forTesting(
                        RecordingAndroidBoundSocketOperations(
                            availableHandles = listOf(42L),
                            resolvedAddresses = emptyList(),
                        ),
                    ).connect(androidCellularNetwork(), "secret.example", 443, 1_000L)

            val timeoutFailure =
                AndroidBoundNetworkSocketConnector
                    .forTesting(
                        RecordingAndroidBoundSocketOperations(
                            availableHandles = listOf(42L),
                            resolvedAddresses = listOf(InetAddress.getByName("127.0.0.1")),
                            connectFailure = SocketTimeoutException("timed out connecting to secret.example"),
                        ),
                    ).connect(androidCellularNetwork(), "secret.example", 443, 1_000L)

            assertEquals(
                BoundSocketConnectResult.Failed(BoundSocketConnectFailure.DnsResolutionFailed),
                dnsFailure,
            )
            assertEquals(
                BoundSocketConnectResult.Failed(BoundSocketConnectFailure.ConnectionTimedOut),
                timeoutFailure,
            )
            assertEquals(
                "Failed(reason=DnsResolutionFailed)",
                dnsFailure.toString(),
            )
            assertEquals(
                "Failed(reason=ConnectionTimedOut)",
                timeoutFailure.toString(),
            )
        }

    @Test
    fun `bound connector rejects invalid public inputs before resolving`() {
        val operations = RecordingAndroidBoundSocketOperations(availableHandles = listOf(42L))
        val connector = AndroidBoundNetworkSocketConnector.forTesting(operations)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            runBlocking {
                connector.connect(androidCellularNetwork(), " ", 443, 1_000L)
            }
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            runBlocking {
                connector.connect(androidCellularNetwork(), "example.test", 0, 1_000L)
            }
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            runBlocking {
                connector.connect(androidCellularNetwork(), "example.test", 443, Int.MAX_VALUE.toLong() + 1L)
            }
        }

        assertEquals(null, operations.resolvedHandle)
    }

    private fun androidCellularNetwork(): NetworkDescriptor =
        NetworkDescriptor(
            id = "android-network:42",
            category = NetworkCategory.Cellular,
            displayName = "Cellular",
            isAvailable = true,
        )
}

private class RecordingAndroidBoundSocketOperations(
    private val availableHandles: List<Long>,
    private val resolvedAddresses: List<InetAddress> = emptyList(),
    private val connectFailure: Exception? = null,
) : AndroidBoundSocketOperations {
    var resolvedHandle: Long? = null
        private set
    var resolvedHost: String? = null
        private set
    var connectedHandle: Long? = null
        private set
    var connectedPort: Int? = null
        private set
    var connectedTimeoutMillis: Int? = null
        private set

    override fun currentNetworkHandles(): List<Long> = availableHandles

    override fun resolveAll(
        handle: Long,
        host: String,
    ): List<InetAddress> {
        resolvedHandle = handle
        resolvedHost = host
        return resolvedAddresses
    }

    override fun connect(
        handle: Long,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ): Socket {
        connectFailure?.let { throw it }
        connectedHandle = handle
        connectedPort = port
        connectedTimeoutMillis = timeoutMillis
        return Socket(address, port)
    }
}

private class RecordingAndroidNetworkObservationSource : AndroidNetworkObservationSource {
    private val current = linkedMapOf<Long, AndroidNetworkObservation>()
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun start() {
        startCount += 1
    }

    override fun observations(): List<AndroidNetworkObservation> = current.values.toList()

    override fun stop() {
        stopCount += 1
    }

    fun publish(observation: AndroidNetworkObservation) {
        current[observation.handle] = observation
    }

    fun lose(handle: Long) {
        current.remove(handle)
    }
}
