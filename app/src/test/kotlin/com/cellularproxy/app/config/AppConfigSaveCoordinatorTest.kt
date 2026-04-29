package com.cellularproxy.app.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.config.ConfigValidationError
import com.cellularproxy.shared.config.ProxyConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigSaveCoordinatorTest {
    @Test
    fun `save derives token-present flag from encrypted Cloudflare token presence`() = withSaveCoordinatorRepositories { plainRepository, sensitiveRepository, sensitiveStore ->
        val coordinator =
            AppConfigSaveCoordinator(
                plainRepository = plainRepository,
                sensitiveRepository = sensitiveRepository,
            )
        val requestedPlainConfig =
            AppConfig.default().copy(
                cloudflare =
                    CloudflareConfig(
                        enabled = true,
                        tunnelTokenPresent = false,
                        managementHostnameLabel = "manage.example.com",
                    ),
            )
        val sensitiveConfig =
            SaveCoordinatorFixtures.sensitiveConfig(
                cloudflareTunnelToken = "cloudflare-token",
            )

        val result =
            coordinator.save(
                plainConfig = requestedPlainConfig,
                sensitiveConfig = sensitiveConfig,
            )

        val saved = assertIs<AppConfigSaveResult.Saved>(result)
        val expectedPlainConfig =
            requestedPlainConfig.copy(
                cloudflare = requestedPlainConfig.cloudflare.copy(tunnelTokenPresent = true),
            )
        assertEquals(expectedPlainConfig, saved.plainConfig)
        assertEquals(sensitiveConfig, saved.sensitiveConfig)
        assertEquals(expectedPlainConfig, plainRepository.load())
        assertIs<SensitiveConfigLoadResult.Loaded>(sensitiveRepository.load())
        assertTrue(SensitiveConfigSecretKeys.cloudflareTunnelToken in sensitiveStore.values)
        assertFalse("cloudflare-token" in sensitiveStore.values.values.joinToString(separator = "\n"))
    }

    @Test
    fun `save clears stale token-present flag and encrypted Cloudflare token when token is absent`() = withSaveCoordinatorRepositories { plainRepository, sensitiveRepository, sensitiveStore ->
        val coordinator =
            AppConfigSaveCoordinator(
                plainRepository = plainRepository,
                sensitiveRepository = sensitiveRepository,
            )
        sensitiveRepository.save(
            SaveCoordinatorFixtures.sensitiveConfig(cloudflareTunnelToken = "stale-cloudflare-token"),
        )
        val requestedPlainConfig =
            AppConfig.default().copy(
                cloudflare =
                    CloudflareConfig(
                        enabled = false,
                        tunnelTokenPresent = true,
                        managementHostnameLabel = "manage.example.com",
                    ),
            )
        val sensitiveConfig = SaveCoordinatorFixtures.sensitiveConfig(cloudflareTunnelToken = null)

        val result =
            coordinator.save(
                plainConfig = requestedPlainConfig,
                sensitiveConfig = sensitiveConfig,
            )

        val saved = assertIs<AppConfigSaveResult.Saved>(result)
        val expectedPlainConfig =
            requestedPlainConfig.copy(
                cloudflare = requestedPlainConfig.cloudflare.copy(tunnelTokenPresent = false),
            )
        assertEquals(expectedPlainConfig, saved.plainConfig)
        assertEquals(expectedPlainConfig, plainRepository.load())
        assertNull(sensitiveStore.values[SensitiveConfigSecretKeys.cloudflareTunnelToken])
        val loadedSensitive = assertIs<SensitiveConfigLoadResult.Loaded>(sensitiveRepository.load())
        assertNull(loadedSensitive.config.cloudflareTunnelToken)
    }

    @Test
    fun `invalid reconciled plain config is rejected without saving plain or sensitive state`() = withSaveCoordinatorRepositories { plainRepository, sensitiveRepository, sensitiveStore ->
        val coordinator =
            AppConfigSaveCoordinator(
                plainRepository = plainRepository,
                sensitiveRepository = sensitiveRepository,
            )
        val invalidPlainConfig =
            AppConfig.default().copy(
                proxy = ProxyConfig(listenHost = " 127.0.0.1 "),
                cloudflare = CloudflareConfig(enabled = true, tunnelTokenPresent = true),
            )
        val sensitiveConfig = SaveCoordinatorFixtures.sensitiveConfig(cloudflareTunnelToken = null)

        val result =
            coordinator.save(
                plainConfig = invalidPlainConfig,
                sensitiveConfig = sensitiveConfig,
            )

        val invalid = assertIs<AppConfigSaveResult.InvalidPlainConfig>(result)
        assertEquals(
            listOf(
                ConfigValidationError.InvalidListenHost,
                ConfigValidationError.MissingCloudflareTunnelToken,
            ),
            invalid.errors,
        )
        assertEquals(
            AppConfig.default().copy(proxy = AppConfig.default().proxy.copy(authEnabled = false)),
            plainRepository.load(),
        )
        assertEquals(0, sensitiveStore.replaceCallCount)
        assertEquals(SensitiveConfigLoadResult.MissingRequiredSecrets, sensitiveRepository.load())
    }
}

private fun withSaveCoordinatorRepositories(
    block: suspend (
        PlainConfigDataStoreRepository,
        SensitiveConfigRepository,
        SaveCoordinatorInMemorySensitiveKeyValueStore,
    ) -> Unit,
) {
    val storeFile = File.createTempFile("cellularproxy-save-config", ".preferences_pb")
    storeFile.delete()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile },
        )
    val plainRepository = PlainConfigDataStoreRepository(dataStore)
    val sensitiveStore = SaveCoordinatorInMemorySensitiveKeyValueStore()
    val sensitiveRepository =
        SensitiveConfigRepository(
            store = sensitiveStore,
            cipher = SaveCoordinatorReversibleTestCipher,
        )

    try {
        runBlocking {
            block(plainRepository, sensitiveRepository, sensitiveStore)
        }
    } finally {
        scope.cancel()
        storeFile.delete()
    }
}

private object SaveCoordinatorFixtures {
    fun sensitiveConfig(cloudflareTunnelToken: String?): SensitiveConfig = SensitiveConfig(
        proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
        managementApiToken = "management-token",
        cloudflareTunnelToken = cloudflareTunnelToken,
    )
}

private class SaveCoordinatorInMemorySensitiveKeyValueStore : SensitiveKeyValueStore {
    val values: MutableMap<String, String> = mutableMapOf()
    var replaceCallCount: Int = 0
        private set

    override fun read(key: String): String? = values[key]

    override fun replace(
        encryptedValues: Map<String, String>,
        keysToRemove: Set<String>,
    ) {
        replaceCallCount += 1
        values.putAll(encryptedValues)
        keysToRemove.forEach(values::remove)
    }

    override fun clear(keys: Set<String>) {
        keys.forEach(values::remove)
    }
}

private object SaveCoordinatorReversibleTestCipher : SensitiveValueCipher {
    override fun encrypt(plainText: String): String = "encrypted:" + plainText.reversed()

    override fun decrypt(encryptedValue: String): String? = encryptedValue
        .takeIf { it.startsWith("encrypted:") }
        ?.removePrefix("encrypted:")
        ?.reversed()
}
