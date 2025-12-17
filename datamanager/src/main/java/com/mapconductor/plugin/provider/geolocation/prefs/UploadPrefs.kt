package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DS_NAME_UPLOAD = "upload_settings"

// Context extension for upload-related DataStore; kept private to avoid collisions.
private val Context.uploadDataStore by preferencesDataStore(DS_NAME_UPLOAD)

/**
 * Thin wrapper around DataStore that exposes raw upload settings.
 *
 * The repository layer is responsible for mapping to enums and applying defaults.
 */
internal class UploadPrefs(private val appContext: Context) {

    private object K {
        val SCHEDULE   = stringPreferencesKey("schedule")
        val INTERVAL_S = intPreferencesKey("interval_sec")
        val ZONE_ID    = stringPreferencesKey("zone_id")
        val FORMAT     = stringPreferencesKey("output_format")
    }

    // ---- Read Flows ----

    val schedule: Flow<String> =
        appContext.uploadDataStore.data.map { it[K.SCHEDULE] ?: UploadSchedule.NIGHTLY.wire }

    val intervalSec: Flow<Int> =
        appContext.uploadDataStore.data.map { it[K.INTERVAL_S] ?: 0 }

    val zoneId: Flow<String> =
        appContext.uploadDataStore.data.map { it[K.ZONE_ID] ?: "Asia/Tokyo" }

    val outputFormat: Flow<String> =
        appContext.uploadDataStore.data.map { it[K.FORMAT] ?: "geojson" }

    // ---- Write APIs ----

    suspend fun setSchedule(schedule: UploadSchedule) {
        appContext.uploadDataStore.edit { it[K.SCHEDULE] = schedule.wire }
    }

    suspend fun setIntervalSec(sec: Int) {
        val clamped = when {
            sec < 0       -> 0
            sec > 86_400  -> 86_400
            else          -> sec
        }
        appContext.uploadDataStore.edit { it[K.INTERVAL_S] = clamped }
    }

    suspend fun setZoneId(zoneId: String) {
        val value = zoneId.ifBlank { "Asia/Tokyo" }
        appContext.uploadDataStore.edit { it[K.ZONE_ID] = value }
    }

    suspend fun setOutputFormat(formatWire: String) {
        val value = formatWire.ifBlank { "geojson" }
        appContext.uploadDataStore.edit { it[K.FORMAT] = value }
    }
}
