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

/**
 * DataStore-backed settings for sampling intervals.
 *
 * New code should prefer the Flow-based APIs; synchronous getters are provided
 * only for legacy code paths.
 */
object SettingsRepository {
    // Defaults and lower bounds (keep compatible with legacy behavior)
    private const val DEFAULT_INTERVAL_SEC = 30     // Default GPS interval in seconds
    private const val MIN_INTERVAL_SEC = 1          // Minimum 1 second
    private const val DEFAULT_DR_SEC = 5            // Default DR interval in seconds
    private const val MIN_DR_SEC = 1                // Minimum 1 second when enabled
    private const val FIRST_TIMEOUT_MS = 700L       // Safety timeout for sync getters

    private val KEY_INTERVAL_SEC: Preferences.Key<Int> = intPreferencesKey("interval_sec")
    private val KEY_DR_INTERVAL_SEC: Preferences.Key<Int> = intPreferencesKey("dr_interval_sec")

    // ---------- Flow ----------

    /** Flow of GPS interval in seconds, including defaults and lower bound. */
    fun intervalSecFlow(context: Context): Flow<Int> =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            (prefs[KEY_INTERVAL_SEC] ?: DEFAULT_INTERVAL_SEC).coerceAtLeast(MIN_INTERVAL_SEC)
        }

    /** Flow of DR interval in seconds.
     *
     * Contract:
     * - 0 means "DR disabled".
     * - When > 0, the value is clamped to [MIN_DR_SEC, +inf).
     */
    fun drIntervalSecFlow(context: Context): Flow<Int> =
        context.applicationContext.settingsDataStore.data.map { prefs ->
            val raw = prefs[KEY_DR_INTERVAL_SEC] ?: DEFAULT_DR_SEC
            if (raw <= 0) 0 else raw.coerceAtLeast(MIN_DR_SEC)
        }

    // ---------- Write ----------

    suspend fun setIntervalSec(context: Context, sec: Int) {
        context.applicationContext.settingsDataStore.edit {
            it[KEY_INTERVAL_SEC] = sec.coerceAtLeast(MIN_INTERVAL_SEC)
        }
    }

    suspend fun setDrIntervalSec(context: Context, sec: Int) {
        context.applicationContext.settingsDataStore.edit {
            it[KEY_DR_INTERVAL_SEC] = if (sec <= 0) 0 else sec.coerceAtLeast(MIN_DR_SEC)
        }
    }

    // ---------- Sync getters (legacy compatibility) ----------

    /** Synchronously returns the current GPS interval in milliseconds. */
    fun currentIntervalMs(context: Context): Long = runBlocking {
        try {
            val sec = withTimeout(FIRST_TIMEOUT_MS) { intervalSecFlow(context).first() }
            sec.coerceAtLeast(MIN_INTERVAL_SEC) * 1000L
        } catch (_: Throwable) {
            DEFAULT_INTERVAL_SEC * 1000L
        }
    }

    /** Synchronously returns the current DR interval in seconds.
     *
     * Contract is the same as [drIntervalSecFlow]:
     * - 0 means "DR disabled".
     * - When > 0, the value is clamped to [MIN_DR_SEC, +inf).
     */
    fun currentDrIntervalSec(context: Context): Int = runBlocking {
        try {
            val v = withTimeout(FIRST_TIMEOUT_MS) { drIntervalSecFlow(context).first() }
            if (v <= 0) 0 else v.coerceAtLeast(MIN_DR_SEC)
        } catch (_: Throwable) {
            DEFAULT_DR_SEC
        }
    }
}
