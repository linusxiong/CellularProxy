package com.cellularproxy.app.config

import android.content.SharedPreferences

class SharedPreferencesSensitiveKeyValueStore(
    private val sharedPreferences: SharedPreferences,
) : SensitiveKeyValueStore {
    override fun read(key: String): String? =
        try {
            sharedPreferences.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }

    override fun replace(
        encryptedValues: Map<String, String>,
        keysToRemove: Set<String>,
    ) {
        require(encryptedValues.keys.intersect(keysToRemove).isEmpty()) {
            "A sensitive key cannot be written and removed in the same operation"
        }
        commitOrThrow(
            sharedPreferences.edit().apply {
                keysToRemove.forEach(::remove)
                encryptedValues.forEach { (key, value) ->
                    putString(key, value)
                }
            },
        )
    }

    override fun clear(keys: Set<String>) {
        commitOrThrow(
            sharedPreferences.edit().apply {
                keys.forEach(::remove)
            },
        )
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Failed to commit sensitive configuration changes" }
    }
}
