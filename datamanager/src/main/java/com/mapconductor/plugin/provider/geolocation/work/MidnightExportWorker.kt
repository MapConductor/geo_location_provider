package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Daily export worker that processes backlog up to the previous day.
 *
 * - Both LocationSample and ExportedDay are stored in storageservice DB.
 *   All access is done via StorageService from this class.
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override suspend fun doWork(): Result {
        val today = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toLocalDate()
        val todayEpochDay = today.toEpochDay()

        // Initial seed: ensure up to 365 days of past records (before yesterday).
        var oldest = StorageService.oldestNotUploadedDay(applicationContext)
        if (oldest == null) {
            val backMaxDays = 365L
            for (off in backMaxDays downTo 1L) {
                val d = today.minusDays(off).toEpochDay()
                if (d < todayEpochDay) {
                    StorageService.ensureExportedDay(applicationContext, d)
                }
            }
            oldest = StorageService.oldestNotUploadedDay(applicationContext)
        }

        // Backlog processing loop (days before "today" only).
        while (oldest != null && oldest.epochDay < todayEpochDay) {
            val target = oldest
            val localDate = LocalDate.ofEpochDay(target.epochDay)
            val startMillis = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val baseName = "glp-${dateFmt.format(localDate)}"

            // Fetch one-day records from storageservice DB via StorageService.
            val records = StorageService.getLocationsBetween(
                ctx = applicationContext,
                from = startMillis,
                to = endMillis,
                softLimit = 1_000_000
            )

            // Generate local GeoJSON as ZIP.
            val exported: Uri? = GeoJsonExporter.exportToDownloads(
                context = applicationContext,
                records = records,
                baseName = baseName,
                compressAsZip = true
            )

            // Mark local export as succeeded (even for empty day).
            StorageService.markExportedLocal(applicationContext, target.epochDay)

            // Upload to Drive only when settings are configured.
            val prefs = AppPrefs.snapshot(applicationContext)
            val engineOk = (prefs.engine == UploadEngine.KOTLIN)
            val folderOk = !prefs.folderId.isNullOrBlank()
            val uploader = if (engineOk && folderOk) {
                UploaderFactory.create(applicationContext, prefs.engine)
            } else null

            var uploadSucceeded = false
            var lastError: String? = null

            if (exported == null) {
                lastError = "No file exported"
                StorageService.markExportError(applicationContext, target.epochDay, lastError)
            } else if (uploader == null) {
                lastError = when {
                    !engineOk -> "Upload not configured (engine=${prefs.engine})"
                    !folderOk -> "Upload not configured (folderId missing)"
                    else -> "Uploader disabled"
                }
                StorageService.markExportError(applicationContext, target.epochDay, lastError)
            } else {
                when (val up = uploader.upload(exported, prefs.folderId!!, "$baseName.zip")) {
                    is UploadResult.Success -> {
                        uploadSucceeded = true
                        StorageService.markUploaded(applicationContext, target.epochDay, null)
                    }
                    is UploadResult.Failure -> {
                        lastError = buildString {
                            append("HTTP ${up.code} ")
                            if (!up.message.isNullOrBlank()) append(up.message)
                            if (!up.body.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(up.body.take(300))
                            }
                        }.ifBlank { "Upload failure" }
                        StorageService.markExportError(applicationContext, target.epochDay, lastError)
                    }
                }
            }

            // Always delete ZIP to avoid filling local storage.
            exported?.let { safeDelete(it) }

            // Only when upload succeeds and there are records, delete that day's records from DB.
            if (uploadSucceeded && records.isNotEmpty()) {
                try {
                    StorageService.deleteLocations(applicationContext, records)
                } catch (e: Throwable) {
                    // Failure is not fatal; they will be retried next run.
                    Log.w(TAG, "Failed to delete uploaded records, will retry next time", e)
                }
            }

            // Move on to the next not-uploaded day.
            oldest = StorageService.oldestNotUploadedDay(applicationContext)
        }

        // After processing, schedule next run at next midnight.
        scheduleNext(applicationContext)
        return Result.success()
    }

    // ---- Class-level helper methods (not local functions). ----

    private fun scheduleNext(context: Context) {
        val delayMs = calcDelayUntilNextMidnightMillis()
        val constraints = Constraints.Builder()
            // Network is required for uploading to Google Drive.
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun calcDelayUntilNextMidnightMillis(): Long {
        val now = ZonedDateTime.now(zone)
        val next = now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    private fun safeDelete(uri: Uri) {
        try {
            applicationContext.contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {
            // Ignore deletion errors (e.g., missing file or permission issues).
        }
    }

    companion object {
        private const val TAG = "MidnightExportWorker"
        private const val UNIQUE_NAME = "midnight-export-worker"

        /** Helper for triggering backlog processing immediately from UI. */
        @JvmStatic
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
