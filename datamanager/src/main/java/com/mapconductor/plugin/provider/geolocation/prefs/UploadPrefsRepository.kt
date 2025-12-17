package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.config.UploadOutputFormat
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for upload-related settings (schedule, interval, timezone).
 *
 * - Provides typed Flows with sensible defaults.
 * - UI and workers should depend on this layer instead of UploadPrefs directly.
 */
class UploadPrefsRepository(context: Context) {

    private val prefs = UploadPrefs(context)

    /** Upload schedule. Defaults to NIGHTLY for backward compatibility. */
    val scheduleFlow: Flow<UploadSchedule> =
        prefs.schedule.map { UploadSchedule.fromString(it) }

    /** Upload interval in seconds. 0 means "every sample". Clamped to [0, 86400]. */
    val intervalSecFlow: Flow<Int> =
        prefs.intervalSec.map { sec ->
            when {
                sec < 0      -> 0
                sec > 86_400 -> 86_400
                else         -> sec
            }
        }

    /** Upload timezone id (for example "Asia/Tokyo"). Defaults to Asia/Tokyo. */
    val zoneIdFlow: Flow<String> =
        prefs.zoneId.map { it.ifBlank { "Asia/Tokyo" } }

    /** Output format for exported logs. Defaults to GEOJSON. */
    val outputFormatFlow: Flow<UploadOutputFormat> =
        prefs.outputFormat.map { UploadOutputFormat.fromString(it) }

    suspend fun setSchedule(schedule: UploadSchedule) = prefs.setSchedule(schedule)

    suspend fun setIntervalSec(sec: Int) = prefs.setIntervalSec(sec)

    suspend fun setZoneId(zoneId: String) = prefs.setZoneId(zoneId)

    suspend fun setOutputFormat(format: UploadOutputFormat) =
        prefs.setOutputFormat(format.wire)
}
