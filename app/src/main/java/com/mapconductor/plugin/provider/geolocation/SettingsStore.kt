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
import kotlin.math.floor
import kotlin.math.max

private val Context.dataStore by preferencesDataStore(name = "geolocation_settings")

object SettingsKeys {
    val UPDATE_INTERVAL_MS: Preferences.Key<Long> = longPreferencesKey("update_interval_ms")
    /** 旧：予測回数（移行用に残す） */
    val PREDICT_COUNT: Preferences.Key<Int> = intPreferencesKey("predict_count")

    /** 新：DR 予測間隔(秒) */
    val DR_INTERVAL_SEC: Preferences.Key<Int> = intPreferencesKey("dr_interval_sec")
}

/**
 * 画面/サービス共通の設定永続化(DataStore)。
 * 既定値: Interval = 30_000ms, 旧予測回数 = 5, DR間隔(秒) = 5(最小)
 */
class SettingsStore(private val appContext: Context) {

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 30_000L
        const val DEFAULT_PREDICT_COUNT: Int = 5   // 旧
        const val MIN_INTERVAL_SEC: Int = 5        // GPS最小
        const val MIN_DR_INTERVAL_SEC: Int = 5     // DR最小
    }

    /** Interval(ms) を Flow で取得（未保存時は既定値） */
    val updateIntervalMsFlow: Flow<Long> =
        appContext.dataStore.data.map { it[SettingsKeys.UPDATE_INTERVAL_MS] ?: DEFAULT_INTERVAL_MS }

    /** 旧：予測回数を Flow で取得（未保存時は既定値） */
    val predictCountFlow: Flow<Int> =
        appContext.dataStore.data.map { it[SettingsKeys.PREDICT_COUNT] ?: DEFAULT_PREDICT_COUNT }

    /** 新：DR 予測間隔(秒) を Flow で取得（未保存時は移行 or 最小5秒） */
    val drIntervalSecFlow: Flow<Int> =
        appContext.dataStore.data.map { pref ->
            val saved = pref[SettingsKeys.DR_INTERVAL_SEC]
            if (saved != null && saved >= MIN_DR_INTERVAL_SEC) {
                saved
            } else {
                // 未保存なら旧設定から移行を試みる
                val gpsMs = pref[SettingsKeys.UPDATE_INTERVAL_MS] ?: DEFAULT_INTERVAL_MS
                val gpsSec = max(MIN_INTERVAL_SEC, (gpsMs / 1000L).toInt())
                val oldCnt = pref[SettingsKeys.PREDICT_COUNT] ?: DEFAULT_PREDICT_COUNT
                // stepSec = gps / (oldCnt + 1) を floor、下限5
                val step = max(MIN_DR_INTERVAL_SEC, floor(gpsSec.toDouble() / (oldCnt + 1)).toInt())
                // 上限は floor(gps/2)
                val upper = max(MIN_DR_INTERVAL_SEC, floor(gpsSec / 2.0).toInt())
                step.coerceAtMost(upper)
            }
        }

    /** 即値取得 */
    suspend fun currentIntervalMs(): Long = updateIntervalMsFlow.first()
    suspend fun currentPredictCount(): Int = predictCountFlow.first()             // 旧
    suspend fun currentDrIntervalSec(): Int = drIntervalSecFlow.first()           // 新

    /** Interval(ms) の保存（負値は0へ矯正） */
    suspend fun setUpdateIntervalMs(ms: Long) {
        appContext.dataStore.edit { it[SettingsKeys.UPDATE_INTERVAL_MS] = ms.coerceAtLeast(0L) }
    }

    /** 旧：予測回数の保存（互換目的で残す） */
    suspend fun setPredictCount(count: Int) {
        appContext.dataStore.edit { it[SettingsKeys.PREDICT_COUNT] = count.coerceAtLeast(0) }
    }

    /** 新：DR 予測間隔(秒) の保存（下限5秒で矯正） */
    suspend fun setDrIntervalSec(sec: Int) {
        appContext.dataStore.edit { it[SettingsKeys.DR_INTERVAL_SEC] = sec.coerceAtLeast(MIN_DR_INTERVAL_SEC) }
    }
}
