package com.cellularproxy.app.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.cellularproxy.shared.proxy.ProxyCredential
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SensitiveConfigRepositoryFactoryTest {
    @Test
    fun `creates repository backed by private named SharedPreferences and Android Keystore cipher`() {
        val context = CapturingContext()
        val repository =
            SensitiveConfigRepositoryFactory.create(
                context = context,
                cipher = AndroidKeystoreSensitiveValueCipher.forTesting(FactoryTestSecretKeyProvider()),
            )
        val config =
            SensitiveConfig(
                proxyCredential = ProxyCredential(username = "proxy-user", password = "proxy-pass"),
                managementApiToken = "management-token",
                cloudflareTunnelToken = "cloudflare-token",
            )

        repository.save(config)

        assertEquals("cellularproxy_sensitive_config", context.lastPreferencesName)
        assertEquals(Context.MODE_PRIVATE, context.lastPreferencesMode)
        val persistedText =
            context.preferences.values.values
                .joinToString(separator = "\n")
        assertFalse("proxy-user" in persistedText)
        assertFalse("proxy-pass" in persistedText)
        assertFalse("management-token" in persistedText)
        assertFalse("cloudflare-token" in persistedText)

        val loaded = assertIs<SensitiveConfigLoadResult.Loaded>(repository.load())
        assertEquals(config, loaded.config)
    }
}

private class FactoryTestSecretKeyProvider : AndroidKeystoreSensitiveValueCipher.SecretKeyProvider {
    private val key: SecretKey =
        KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }

    override fun getOrCreateKey(): SecretKey = key
}

private class CapturingContext : ContextWrapper(null) {
    val preferences = FactoryTestSharedPreferences()
    var lastPreferencesName: String? = null
        private set
    var lastPreferencesMode: Int? = null
        private set

    override fun getSharedPreferences(
        name: String?,
        mode: Int,
    ): SharedPreferences {
        lastPreferencesName = name
        lastPreferencesMode = mode
        return preferences
    }
}

private class FactoryTestSharedPreferences : SharedPreferences {
    val values: MutableMap<String, String> = mutableMapOf()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = defValue

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val writes: MutableMap<String, String> = linkedMapOf()
        private val removals: MutableSet<String> = linkedSetOf()

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor {
            requireNotNull(key)
            requireNotNull(value)
            writes[key] = value
            removals.remove(key)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            requireNotNull(key)
            removals.add(key)
            writes.remove(key)
            return this
        }

        override fun commit(): Boolean {
            removals.forEach(values::remove)
            values.putAll(writes)
            return true
        }

        override fun apply() {
            commit()
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            writes.clear()
            removals.clear()
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = this

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = this

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = this

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = this
    }
}
