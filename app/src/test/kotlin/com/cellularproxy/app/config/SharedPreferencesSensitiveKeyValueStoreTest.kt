package com.cellularproxy.app.config

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SharedPreferencesSensitiveKeyValueStoreTest {
    @Test
    fun `reads values from SharedPreferences by exact key`() {
        val preferences =
            FakeSharedPreferences(
                SensitiveConfigSecretKeys.managementApiToken to "encrypted-management-token",
            )
        val store = SharedPreferencesSensitiveKeyValueStore(preferences)

        assertEquals(
            "encrypted-management-token",
            store.read(SensitiveConfigSecretKeys.managementApiToken),
        )
        assertNull(store.read(SensitiveConfigSecretKeys.proxyAuthCredential))
    }

    @Test
    fun `replace writes encrypted values and removes stale keys in one commit`() {
        val preferences =
            FakeSharedPreferences(
                SensitiveConfigSecretKeys.proxyAuthCredential to "old-credential",
                SensitiveConfigSecretKeys.managementApiToken to "old-token",
                SensitiveConfigSecretKeys.cloudflareTunnelToken to "old-cloudflare-token",
                "foreign.secret" to "foreign-value",
            )
        val store = SharedPreferencesSensitiveKeyValueStore(preferences)

        store.replace(
            encryptedValues =
                mapOf(
                    SensitiveConfigSecretKeys.proxyAuthCredential to "new-credential",
                    SensitiveConfigSecretKeys.managementApiToken to "new-token",
                ),
            keysToRemove = setOf(SensitiveConfigSecretKeys.cloudflareTunnelToken),
        )

        assertEquals("new-credential", preferences.values[SensitiveConfigSecretKeys.proxyAuthCredential])
        assertEquals("new-token", preferences.values[SensitiveConfigSecretKeys.managementApiToken])
        assertFalse(SensitiveConfigSecretKeys.cloudflareTunnelToken in preferences.values)
        assertEquals("foreign-value", preferences.values["foreign.secret"])
        assertEquals(1, preferences.commitCount)
        assertEquals(0, preferences.applyCount)
    }

    @Test
    fun `clear removes only requested keys in one commit`() {
        val preferences =
            FakeSharedPreferences(
                SensitiveConfigSecretKeys.proxyAuthCredential to "credential",
                SensitiveConfigSecretKeys.managementApiToken to "token",
                "foreign.secret" to "foreign-value",
            )
        val store = SharedPreferencesSensitiveKeyValueStore(preferences)

        store.clear(
            setOf(
                SensitiveConfigSecretKeys.proxyAuthCredential,
                SensitiveConfigSecretKeys.managementApiToken,
            ),
        )

        assertEquals(mapOf("foreign.secret" to "foreign-value"), preferences.values)
        assertEquals(1, preferences.commitCount)
        assertEquals(0, preferences.applyCount)
    }

    @Test
    fun `replace rejects overlapping written and removed keys`() {
        val store = SharedPreferencesSensitiveKeyValueStore(FakeSharedPreferences())

        assertFailsWith<IllegalArgumentException> {
            store.replace(
                encryptedValues = mapOf(SensitiveConfigSecretKeys.cloudflareTunnelToken to "token"),
                keysToRemove = setOf(SensitiveConfigSecretKeys.cloudflareTunnelToken),
            )
        }
    }

    @Test
    fun `failed SharedPreferences commit is surfaced`() {
        val preferences = FakeSharedPreferences(commitSucceeds = false)
        val store = SharedPreferencesSensitiveKeyValueStore(preferences)

        assertFailsWith<IllegalStateException> {
            store.replace(
                encryptedValues = mapOf(SensitiveConfigSecretKeys.managementApiToken to "token"),
                keysToRemove = emptySet(),
            )
        }
        assertFailsWith<IllegalStateException> {
            store.clear(setOf(SensitiveConfigSecretKeys.managementApiToken))
        }
    }

    @Test
    fun `read treats non-string stored values as absent`() {
        val preferences = FakeSharedPreferences(throwOnGetString = true)
        val store = SharedPreferencesSensitiveKeyValueStore(preferences)

        assertNull(store.read(SensitiveConfigSecretKeys.managementApiToken))
    }
}

private class FakeSharedPreferences(
    vararg initialValues: Pair<String, String>,
    private val commitSucceeds: Boolean = true,
    private val throwOnGetString: Boolean = false,
) : SharedPreferences {
    val values: MutableMap<String, String> = initialValues.toMap().toMutableMap()
    var commitCount: Int = 0
        private set
    var applyCount: Int = 0
        private set

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = if (throwOnGetString) {
        throw ClassCastException("Stored value is not a string")
    } else {
        values[key] ?: defValue
    }

    override fun edit(): SharedPreferences.Editor = FakeEditor()

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

    private inner class FakeEditor : SharedPreferences.Editor {
        private val writes: MutableMap<String, String> = linkedMapOf()
        private val removals: MutableSet<String> = linkedSetOf()
        private var clearRequested: Boolean = false

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

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            writes.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            commitCount += 1
            if (!commitSucceeds) {
                return false
            }
            if (clearRequested) {
                values.clear()
            }
            removals.forEach(values::remove)
            values.putAll(writes)
            return true
        }

        override fun apply() {
            applyCount += 1
            commit()
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
