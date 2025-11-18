package com.mapconductor.plugin.provider.storageservice.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val Context.settingsDataStore by preferencesDataStore(name = "geolocation_settings")

object SettingsRepository {
    // 旧仕様に合わせた既定値 / 下限
    private const val DEFAULT_INTERVAL_SEC = 30      // GPS取得間隔 既定
    private const val MIN_INTERVAL_SEC     = 5       // 最小5秒
    private const val DEFAULT_DR_SEC       = 5       // DR間隔 既定
    private const val MIN_DR_SEC           = 5       // 最小5秒
    private const val FIRST_TIMEOUT_MS     = 700L    // 同期取得のタイムアウト保険

    private val KEY_INTERVAL_SEC: Preferences.Key<Int> = intPreferencesKey("interval_sec")
    private val KEY_DR_INTERVAL_SEC: Preferences.Key<Int> = intPreferencesKey("dr_interval_sec")

    // ---------- Flow ----------
    /** GPS取得間隔(秒) Flow（既定・下限を反映） */
    fun intervalSecFlow(context: Context): Flow<Int> =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            (prefs[KEY_INTERVAL_SEC] ?: DEFAULT_INTERVAL_SEC).coerceAtLeast(MIN_INTERVAL_SEC)
        }

    /** DR間隔(秒) Flow（既定・下限を反映） */
    fun drIntervalSecFlow(context: Context): Flow<Int> =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            (prefs[KEY_DR_INTERVAL_SEC] ?: DEFAULT_DR_SEC).coerceAtLeast(MIN_DR_SEC)
        }

    // ---------- Write ----------
    suspend fun setIntervalSec(context: Context, sec: Int) {
        context.applicationContext.settingsDataStore.edit {
            it[KEY_INTERVAL_SEC] = sec.coerceAtLeast(MIN_INTERVAL_SEC)
        }
    }

    suspend fun setDrIntervalSec(context: Context, sec: Int) {
        context.applicationContext.settingsDataStore.edit {
            it[KEY_DR_INTERVAL_SEC] = sec.coerceAtLeast(MIN_DR_SEC)
        }
    }

    // ---------- Sync getters（既存コード互換用） ----------
    /** すぐ“現在のGPS間隔(ミリ秒)”が欲しい時用（旧 currentIntervalMs() 相当） */
    fun currentIntervalMs(context: Context): Long = runBlocking {
        try {
            val sec = withTimeout(FIRST_TIMEOUT_MS) { intervalSecFlow(context).first() }
            (sec.coerceAtLeast(MIN_INTERVAL_SEC) * 1000L)
        } catch (_: Throwable) {
            DEFAULT_INTERVAL_SEC * 1000L
        }
    }

    /** すぐ“現在のDR間隔(秒)”が欲しい時用（旧 currentDrIntervalSec() 相当） */
    fun currentDrIntervalSec(context: Context): Int = runBlocking {
        try {
            withTimeout(FIRST_TIMEOUT_MS) { drIntervalSecFlow(context).first() }
                .coerceAtLeast(MIN_DR_SEC)
        } catch (_: Throwable) {
            DEFAULT_DR_SEC
        }
    }
}
