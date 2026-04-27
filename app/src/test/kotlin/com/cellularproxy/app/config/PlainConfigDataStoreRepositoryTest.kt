package com.cellularproxy.app.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.NetworkConfig
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.config.RotationConfig
import com.cellularproxy.shared.config.RouteTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class PlainConfigDataStoreRepositoryTest {
    @Test
    fun `loads defaults from empty DataStore`() =
        withRepository { repository, _ ->
            assertEquals(AppConfig.default(), repository.configFlow.first())
            assertEquals(AppConfig.default(), repository.load())
        }

    @Test
    fun `saves config and replaces stale nullable values`() =
        withRepository { repository, dataStore ->
            val configWithHostname =
                AppConfig(
                    proxy =
                        ProxyConfig(
                            listenHost = "127.0.0.1",
                            listenPort = 8_888,
                            authEnabled = false,
                        ),
                    network = NetworkConfig(defaultRoutePolicy = RouteTarget.Vpn),
                    rotation =
                        RotationConfig(
                            strictIpChangeRequired = true,
                            mobileDataOffDelay = 5.seconds,
                            networkReturnTimeout = 90.seconds,
                            cooldown = 240.seconds,
                        ),
                    cloudflare =
                        CloudflareConfig(
                            enabled = true,
                            tunnelTokenPresent = true,
                            managementHostnameLabel = "manage.example.com",
                        ),
                )
            repository.save(configWithHostname)
            assertEquals(configWithHostname, repository.load())

            val configWithoutHostname =
                AppConfig.default().copy(
                    cloudflare =
                        AppConfig.default().cloudflare.copy(
                            tunnelTokenPresent = true,
                        ),
                )
            repository.save(configWithoutHostname)

            assertEquals(configWithoutHostname, repository.load())
            assertFalse(
                PlainConfigPreferenceKeys.cloudflareManagementHostnameLabel in
                    dataStore.data
                        .first()
                        .asMap()
                        .keys,
            )
        }
}

private fun withRepository(block: suspend (PlainConfigDataStoreRepository, DataStore<Preferences>) -> Unit) {
    val storeFile = File.createTempFile("cellularproxy-config", ".preferences_pb")
    storeFile.delete()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile },
        )
    val repository = PlainConfigDataStoreRepository(dataStore)

    try {
        runBlocking {
            block(repository, dataStore)
        }
    } finally {
        scope.cancel()
        storeFile.delete()
    }
}
