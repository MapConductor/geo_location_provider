package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "geolocation_settings")

object SettingsKeys {
    val UPDATE_INTERVAL_MS: Preferences.Key<Long> = longPreferencesKey("update_interval_ms")
    val PREDICT_COUNT: Preferences.Key<Int> = intPreferencesKey("predict_count") // ★ 追加
}

/**
 * 更新間隔（ms）＋ 予測回数（int）を扱う DataStore ユーティリティ。
 * 既存との後方互換を維持しつつ、predict_count を追加。
 */
class SettingsStore(private val appContext: Context) {

    /** 保存済みの更新間隔（ms）。未保存時は null */
    val updateIntervalMsFlow: Flow<Long?> =
        appContext.dataStore.data.map { it[SettingsKeys.UPDATE_INTERVAL_MS] }

    /** 保存済みの予測回数。未保存時は null */
    val predictCountFlow: Flow<Int?> =
        appContext.dataStore.data.map { it[SettingsKeys.PREDICT_COUNT] }

    /** 保存（ms） */
    suspend fun setUpdateIntervalMs(ms: Long) {
        appContext.dataStore.edit { it[SettingsKeys.UPDATE_INTERVAL_MS] = ms.coerceAtLeast(0L) }
    }

    /** 予測回数の保存（負値は0へ） */
    suspend fun setPredictCount(count: Int) {
        appContext.dataStore.edit { it[SettingsKeys.PREDICT_COUNT] = count.coerceAtLeast(0) }
    }
}
