package com.cellularproxy.app.config

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

object CellularProxyPlainConfigStore {
    fun repository(context: Context): PlainConfigDataStoreRepository =
        PlainConfigDataStoreRepository(context.applicationContext.cellularProxyPlainConfigDataStore)
}

private val Context.cellularProxyPlainConfigDataStore by preferencesDataStore(
    name = "cellularproxy_plain_config",
)
