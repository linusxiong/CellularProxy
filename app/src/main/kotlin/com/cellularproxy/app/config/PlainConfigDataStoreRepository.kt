package com.cellularproxy.app.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.cellularproxy.shared.config.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class PlainConfigDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val configFlow: Flow<AppConfig> =
        dataStore.data.map(PlainConfigPreferencesMapper::fromPreferences)

    suspend fun load(): AppConfig = configFlow.first()

    suspend fun save(config: AppConfig) {
        dataStore.edit { preferences ->
            PlainConfigPreferencesMapper.replacePreferences(preferences, config)
        }
    }
}
