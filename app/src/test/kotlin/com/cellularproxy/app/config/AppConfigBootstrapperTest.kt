package com.cellularproxy.app.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.CloudflareConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class AppConfigBootstrapperTest {
    @Test
    fun `first run generates required sensitive defaults and saves them encrypted`() = withPlainRepository { plainRepository, _ ->
        val sensitiveStore = BootstrapInMemorySensitiveKeyValueStore()
        val sensitiveRepository = SensitiveConfigRepository(
            store = sensitiveStore,
            cipher = BootstrapReversibleTestCipher,
        )
        val bootstrapper = AppConfigBootstrapper(
            plainRepository = plainRepository,
            sensitiveRepository = sensitiveRepository,
            generator = FixedSensitiveConfigGenerator,
        )

        val result = bootstrapper.loadOrCreate()

        val ready = assertIs<AppConfigBootstrapResult.Ready>(result)
        assertTrue(ready.createdDefaultSecrets)
        assertFalse(ready.reconciledPlainConfig)
        assertEquals(AppConfig.default(), ready.plainConfig)
        assertEquals(
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "generated-proxy", password = "generated-password"),
                managementApiToken = "generated-management-token",
            ),
            ready.sensitiveConfig,
        )
        assertIs<SensitiveConfigLoadResult.Loaded>(sensitiveRepository.load())
        val persistedText = sensitiveStore.values.values.joinToString(separator = "\n")
        assertFalse("generated-proxy" in persistedText)
        assertFalse("generated-password" in persistedText)
        assertFalse("generated-management-token" in persistedText)
    }

    @Test
    fun `reconciles plain Cloudflare token-present flag to encrypted token presence`() =
        withPlainRepository { plainRepository, _ ->
            val stalePlainConfig = AppConfig.default().copy(
                cloudflare = CloudflareConfig(
                    enabled = true,
                    tunnelTokenPresent = true,
                    managementHostnameLabel = "manage.example.com",
                ),
            )
            plainRepository.save(stalePlainConfig)
            val sensitiveRepository = SensitiveConfigRepository(
                store = BootstrapInMemorySensitiveKeyValueStore(),
                cipher = BootstrapReversibleTestCipher,
            )
            sensitiveRepository.save(FixedSensitiveConfigGenerator.generateDefaultSensitiveConfig())
            val bootstrapper = AppConfigBootstrapper(
                plainRepository = plainRepository,
                sensitiveRepository = sensitiveRepository,
                generator = FixedSensitiveConfigGenerator,
            )

            val result = bootstrapper.loadOrCreate()

            val ready = assertIs<AppConfigBootstrapResult.Ready>(result)
            assertFalse(ready.createdDefaultSecrets)
            assertTrue(ready.reconciledPlainConfig)
            assertEquals(
                stalePlainConfig.copy(cloudflare = stalePlainConfig.cloudflare.copy(tunnelTokenPresent = false)),
                ready.plainConfig,
            )
            assertEquals(ready.plainConfig, plainRepository.load())
        }

    @Test
    fun `marks Cloudflare token present when encrypted token exists`() = withPlainRepository { plainRepository, _ ->
        val sensitiveRepository = SensitiveConfigRepository(
            store = BootstrapInMemorySensitiveKeyValueStore(),
            cipher = BootstrapReversibleTestCipher,
        )
        val expectedSensitiveConfig = FixedSensitiveConfigGenerator.generateDefaultSensitiveConfig()
            .copy(cloudflareTunnelToken = "cloudflare-token")
        sensitiveRepository.save(expectedSensitiveConfig)
        val bootstrapper = AppConfigBootstrapper(
            plainRepository = plainRepository,
            sensitiveRepository = sensitiveRepository,
            generator = FixedSensitiveConfigGenerator,
        )

        val result = bootstrapper.loadOrCreate()

        val ready = assertIs<AppConfigBootstrapResult.Ready>(result)
        assertFalse(ready.createdDefaultSecrets)
        assertTrue(ready.reconciledPlainConfig)
        assertEquals(
            AppConfig.default().copy(
                cloudflare = AppConfig.default().cloudflare.copy(tunnelTokenPresent = true),
            ),
            ready.plainConfig,
        )
        assertEquals(expectedSensitiveConfig, ready.sensitiveConfig)
        assertEquals(ready.plainConfig, plainRepository.load())
    }

    @Test
    fun `invalid existing sensitive secrets are reported without overwriting them`() =
        withPlainRepository { plainRepository, _ ->
            val sensitiveStore = BootstrapInMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to BootstrapReversibleTestCipher.encrypt("missing-separator"),
                SensitiveConfigSecretKeys.managementApiToken to BootstrapReversibleTestCipher.encrypt("management-token"),
            )
            val bootstrapper = AppConfigBootstrapper(
                plainRepository = plainRepository,
                sensitiveRepository = SensitiveConfigRepository(
                    store = sensitiveStore,
                    cipher = BootstrapReversibleTestCipher,
                ),
                generator = FixedSensitiveConfigGenerator,
            )

            val result = bootstrapper.loadOrCreate()

            assertEquals(
                AppConfigBootstrapResult.InvalidSensitiveConfig(
                    SensitiveConfigInvalidReason.InvalidProxyCredential,
                ),
                result,
            )
            assertEquals(0, sensitiveStore.replaceCallCount)
        }

    @Test
    fun `partially missing sensitive secrets are reported without overwriting existing secrets`() =
        withPlainRepository { plainRepository, _ ->
            val existingProxyCredential = BootstrapReversibleTestCipher.encrypt("existing-user:existing-pass")
            val existingCloudflareToken = BootstrapReversibleTestCipher.encrypt("existing-cloudflare-token")
            val sensitiveStore = BootstrapInMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to existingProxyCredential,
                SensitiveConfigSecretKeys.cloudflareTunnelToken to existingCloudflareToken,
            )
            val bootstrapper = AppConfigBootstrapper(
                plainRepository = plainRepository,
                sensitiveRepository = SensitiveConfigRepository(
                    store = sensitiveStore,
                    cipher = BootstrapReversibleTestCipher,
                ),
                generator = FixedSensitiveConfigGenerator,
            )

            val result = bootstrapper.loadOrCreate()

            assertEquals(
                AppConfigBootstrapResult.InvalidSensitiveConfig(
                    SensitiveConfigInvalidReason.PartiallyMissingRequiredSecrets,
                ),
                result,
            )
            assertEquals(0, sensitiveStore.replaceCallCount)
            assertEquals(existingProxyCredential, sensitiveStore.values[SensitiveConfigSecretKeys.proxyAuthCredential])
            assertEquals(existingCloudflareToken, sensitiveStore.values[SensitiveConfigSecretKeys.cloudflareTunnelToken])
        }
}

private fun withPlainRepository(
    block: suspend (PlainConfigDataStoreRepository, DataStore<Preferences>) -> Unit,
) {
    val storeFile = File.createTempFile("cellularproxy-bootstrap-config", ".preferences_pb")
    storeFile.delete()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
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

private object FixedSensitiveConfigGenerator : SensitiveConfigGenerator {
    override fun generateDefaultSensitiveConfig(): SensitiveConfig = SensitiveConfig(
        proxyCredential = ProxyCredential(username = "generated-proxy", password = "generated-password"),
        managementApiToken = "generated-management-token",
    )
}

private class BootstrapInMemorySensitiveKeyValueStore(
    vararg initialValues: Pair<String, String>,
) : SensitiveKeyValueStore {
    val values: MutableMap<String, String> = initialValues.toMap().toMutableMap()
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

private object BootstrapReversibleTestCipher : SensitiveValueCipher {
    override fun encrypt(plainText: String): String = "encrypted:" + plainText.reversed()

    override fun decrypt(encryptedValue: String): String? =
        encryptedValue
            .takeIf { it.startsWith("encrypted:") }
            ?.removePrefix("encrypted:")
            ?.reversed()
}
