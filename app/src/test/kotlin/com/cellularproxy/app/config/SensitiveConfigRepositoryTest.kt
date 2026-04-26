package com.cellularproxy.app.config

import com.cellularproxy.shared.proxy.ProxyCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveConfigRepositoryTest {
    @Test
    fun `saves and loads sensitive configuration through encrypted store values`() {
        val backingStore = InMemorySensitiveKeyValueStore()
        val repository = SensitiveConfigRepository(
            store = backingStore,
            cipher = ReversibleTestCipher,
        )
        val config = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
            managementApiToken = "management-token",
            cloudflareTunnelToken = "cloudflare-token",
        )

        repository.save(config)

        assertEquals(
            setOf(
                SensitiveConfigSecretKeys.proxyAuthCredential,
                SensitiveConfigSecretKeys.managementApiToken,
                SensitiveConfigSecretKeys.cloudflareTunnelToken,
            ),
            backingStore.values.keys,
        )
        val persistedText = backingStore.values.values.joinToString(separator = "\n")
        assertFalse("proxy-user" in persistedText)
        assertFalse("proxy-pass" in persistedText)
        assertFalse("management-token" in persistedText)
        assertFalse("cloudflare-token" in persistedText)

        val loaded = assertIs<SensitiveConfigLoadResult.Loaded>(repository.load())
        assertEquals(config, loaded.config)
    }

    @Test
    fun `saving without Cloudflare tunnel token clears stale token value`() {
        val backingStore = InMemorySensitiveKeyValueStore()
        val repository = SensitiveConfigRepository(
            store = backingStore,
            cipher = ReversibleTestCipher,
        )

        repository.save(sensitiveConfig(cloudflareTunnelToken = "old-cloudflare-token"))
        repository.save(sensitiveConfig(cloudflareTunnelToken = null))

        assertFalse(SensitiveConfigSecretKeys.cloudflareTunnelToken in backingStore.values.keys)
        val loaded = assertIs<SensitiveConfigLoadResult.Loaded>(repository.load())
        assertNull(loaded.config.cloudflareTunnelToken)
    }

    @Test
    fun `missing required secrets produce missing result`() {
        val repository = SensitiveConfigRepository(
            store = InMemorySensitiveKeyValueStore(),
            cipher = ReversibleTestCipher,
        )

        assertEquals(SensitiveConfigLoadResult.MissingRequiredSecrets, repository.load())
    }

    @Test
    fun `partially missing required secrets produce typed invalid result`() {
        listOf(
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
            ),
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
            ),
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.cloudflareTunnelToken to ReversibleTestCipher.encrypt("cloudflare-token"),
            ),
        ).forEach { backingStore ->
            val repository = SensitiveConfigRepository(
                store = backingStore,
                cipher = ReversibleTestCipher,
            )

            assertEquals(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.PartiallyMissingRequiredSecrets),
                repository.load(),
            )
        }
    }

    @Test
    fun `corrupt encrypted proxy credential produces invalid result`() {
        val repository = SensitiveConfigRepository(
            store = InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("missing-separator"),
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
            ),
            cipher = ReversibleTestCipher,
        )

        assertEquals(
            SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidProxyCredential),
            repository.load(),
        )
    }

    @Test
    fun `undecryptable secrets produce invalid result`() {
        listOf(
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to "not-encrypted",
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
            ),
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
                SensitiveConfigSecretKeys.managementApiToken to "not-encrypted",
            ),
            InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
                SensitiveConfigSecretKeys.cloudflareTunnelToken to "not-encrypted",
            ),
        ).forEach { backingStore ->
            val repository = SensitiveConfigRepository(
                store = backingStore,
                cipher = ReversibleTestCipher,
            )

            assertEquals(
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret),
                repository.load(),
            )
        }
    }

    @Test
    fun `blank decrypted tokens produce typed invalid results`() {
        val blankManagementTokenRepository = SensitiveConfigRepository(
            store = InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt(" "),
            ),
            cipher = ReversibleTestCipher,
        )
        val blankCloudflareTokenRepository = SensitiveConfigRepository(
            store = InMemorySensitiveKeyValueStore(
                SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
                SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
                SensitiveConfigSecretKeys.cloudflareTunnelToken to ReversibleTestCipher.encrypt(" "),
            ),
            cipher = ReversibleTestCipher,
        )

        assertEquals(
            SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidManagementApiToken),
            blankManagementTokenRepository.load(),
        )
        assertEquals(
            SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidCloudflareTunnelToken),
            blankCloudflareTokenRepository.load(),
        )
    }

    @Test
    fun `proxy passwords containing colon round trip correctly`() {
        val repository = SensitiveConfigRepository(
            store = InMemorySensitiveKeyValueStore(),
            cipher = ReversibleTestCipher,
        )
        val config = SensitiveConfig(
            proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy:pass:with:colon"),
            managementApiToken = "management-token",
        )

        repository.save(config)

        val loaded = assertIs<SensitiveConfigLoadResult.Loaded>(repository.load())
        assertEquals(config, loaded.config)
    }

    @Test
    fun `save commits repository secrets through one atomic replace operation`() {
        val backingStore = InMemorySensitiveKeyValueStore(
            SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("old-user:old-pass"),
            SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("old-token"),
            SensitiveConfigSecretKeys.cloudflareTunnelToken to ReversibleTestCipher.encrypt("stale-cloudflare-token"),
        )
        val repository = SensitiveConfigRepository(
            store = backingStore,
            cipher = ReversibleTestCipher,
        )

        repository.save(sensitiveConfig(cloudflareTunnelToken = null))

        assertEquals(1, backingStore.replaceCallCount)
        assertEquals(
            setOf(SensitiveConfigSecretKeys.cloudflareTunnelToken),
            backingStore.lastKeysToRemove,
        )
        assertEquals(
            setOf(
                SensitiveConfigSecretKeys.proxyAuthCredential,
                SensitiveConfigSecretKeys.managementApiToken,
            ),
            backingStore.lastReplacementValues.keys,
        )
    }

    @Test
    fun `clear removes only repository-owned secret keys`() {
        val foreignKey = "other.secret"
        val backingStore = InMemorySensitiveKeyValueStore(
            SensitiveConfigSecretKeys.proxyAuthCredential to ReversibleTestCipher.encrypt("proxy-user:proxy-pass"),
            SensitiveConfigSecretKeys.managementApiToken to ReversibleTestCipher.encrypt("management-token"),
            SensitiveConfigSecretKeys.cloudflareTunnelToken to ReversibleTestCipher.encrypt("cloudflare-token"),
            foreignKey to ReversibleTestCipher.encrypt("foreign-value"),
        )
        val repository = SensitiveConfigRepository(
            store = backingStore,
            cipher = ReversibleTestCipher,
        )

        repository.clear()

        assertEquals(setOf(foreignKey), backingStore.values.keys)
    }

    @Test
    fun `sensitive config diagnostics redact raw secrets`() {
        val config = sensitiveConfig(cloudflareTunnelToken = "cloudflare-token")
        val rendered = listOf(
            config.toString(),
            SensitiveConfigLoadResult.Loaded(config).toString(),
        ).joinToString(separator = "\n")

        assertTrue("[REDACTED]" in rendered)
        assertFalse("proxy-user" in rendered)
        assertFalse("proxy-pass" in rendered)
        assertFalse("management-token" in rendered)
        assertFalse("cloudflare-token" in rendered)
    }

    private fun sensitiveConfig(cloudflareTunnelToken: String?) = SensitiveConfig(
        proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
        managementApiToken = "management-token",
        cloudflareTunnelToken = cloudflareTunnelToken,
    )
}

private class InMemorySensitiveKeyValueStore(
    vararg initialValues: Pair<String, String>,
) : SensitiveKeyValueStore {
    val values: MutableMap<String, String> = initialValues.toMap().toMutableMap()
    var replaceCallCount: Int = 0
        private set
    var lastReplacementValues: Map<String, String> = emptyMap()
        private set
    var lastKeysToRemove: Set<String> = emptySet()
        private set

    override fun read(key: String): String? = values[key]

    override fun replace(
        encryptedValues: Map<String, String>,
        keysToRemove: Set<String>,
    ) {
        replaceCallCount += 1
        lastReplacementValues = encryptedValues.toMap()
        lastKeysToRemove = keysToRemove.toSet()
        values.putAll(encryptedValues)
        keysToRemove.forEach(values::remove)
    }

    override fun clear(keys: Set<String>) {
        keys.forEach(values::remove)
    }
}

private object ReversibleTestCipher : SensitiveValueCipher {
    override fun encrypt(plainText: String): String = "encrypted:" + plainText.reversed()

    override fun decrypt(encryptedValue: String): String? =
        encryptedValue
            .takeIf { it.startsWith("encrypted:") }
            ?.removePrefix("encrypted:")
            ?.reversed()
}
