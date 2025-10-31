package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "geolocation_settings")

object SettingsKeys {
    val UPDATE_INTERVAL_MS: Preferences.Key<Long> = longPreferencesKey("update_interval_ms")
    val PREDICT_COUNT: Preferences.Key<Int> = intPreferencesKey("predict_count")
}

/**
 * 画面/サービス共通の設定永続化(DataStore)。
 * 既定値: Interval = 30_000ms, 予測回数 = 5
 */
class SettingsStore(private val appContext: Context) {

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 30_000L
        const val DEFAULT_PREDICT_COUNT: Int = 5
        const val MIN_INTERVAL_SEC: Int = 5
        const val MIN_PREDICT_COUNT: Int = 1
    }

    /** Interval(ms) を Flow で取得（未保存時は既定値） */
    val updateIntervalMsFlow: Flow<Long> =
        appContext.dataStore.data.map { it[SettingsKeys.UPDATE_INTERVAL_MS] ?: DEFAULT_INTERVAL_MS }

    /** 予測回数を Flow で取得（未保存時は既定値） */
    val predictCountFlow: Flow<Int> =
        appContext.dataStore.data.map { it[SettingsKeys.PREDICT_COUNT] ?: DEFAULT_PREDICT_COUNT }

    /** 即値取得（内部利用） */
    suspend fun currentIntervalMs(): Long = updateIntervalMsFlow.first()
    suspend fun currentPredictCount(): Int = predictCountFlow.first()

    /** Interval(ms) の保存（負値は0へ矯正） */
    suspend fun setUpdateIntervalMs(ms: Long) {
        appContext.dataStore.edit { it[SettingsKeys.UPDATE_INTERVAL_MS] = ms.coerceAtLeast(0L) }
    }

    /** 予測回数の保存（負値は0へ矯正） */
    suspend fun setPredictCount(count: Int) {
        appContext.dataStore.edit { it[SettingsKeys.PREDICT_COUNT] = count.coerceAtLeast(0) }
    }
}
