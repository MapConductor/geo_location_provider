package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "geolocation_settings")

object SettingsKeys {
    val UPDATE_INTERVAL_MS = longPreferencesKey("update_interval_ms")
}

/** 更新間隔（ms）だけを扱う DataStore ユーティリティ */
class SettingsStore(private val appContext: Context) {

    /** 保存済みの更新間隔（ms）。未保存時は null */
    val updateIntervalMsFlow: Flow<Long?> =
        appContext.dataStore.data.map { it[SettingsKeys.UPDATE_INTERVAL_MS] }

    /** 保存（ms） */
    suspend fun setUpdateIntervalMs(ms: Long) {
        appContext.dataStore.edit { it[SettingsKeys.UPDATE_INTERVAL_MS] = ms }
    }
}
