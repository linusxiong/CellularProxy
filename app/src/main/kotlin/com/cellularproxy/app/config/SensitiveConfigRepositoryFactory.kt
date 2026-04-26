package com.cellularproxy.app.config

import android.content.Context

object SensitiveConfigRepositoryFactory {
    private const val SENSITIVE_PREFERENCES_NAME = "cellularproxy_sensitive_config"

    fun create(
        context: Context,
        cipher: SensitiveValueCipher = AndroidKeystoreSensitiveValueCipher.create(),
    ): SensitiveConfigRepository {
        val sharedPreferences = context.getSharedPreferences(
            SENSITIVE_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        return SensitiveConfigRepository(
            store = SharedPreferencesSensitiveKeyValueStore(sharedPreferences),
            cipher = cipher,
        )
    }
}
