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
import androidx.work.workDataOf
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.config.UploadOutputFormat
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.export.GpxExporter
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.prefs.UploadPrefsRepository
import com.mapconductor.plugin.provider.storageservice.StorageService
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
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

    private var zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private var outputFormat: UploadOutputFormat = UploadOutputFormat.GEOJSON
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val drivePrefs: DrivePrefsRepository = DrivePrefsRepository(appContext)
    private val uploadPrefs: UploadPrefsRepository = UploadPrefsRepository(appContext)

    companion object {
        private const val TAG = "MidnightExportWorker"
        private const val UNIQUE_NAME = "midnight-export-worker"
        private const val KEY_FORCE_FULL_SCAN = "force_full_scan"

        /** Helper for triggering backlog processing immediately from UI. */
        @JvmStatic
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val data = workDataOf(KEY_FORCE_FULL_SCAN to true)
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }

    override suspend fun doWork(): Result {
        // Resolve timezone, schedule, and output format from UploadPrefs.
        val schedule = runCatching { uploadPrefs.scheduleFlow.first() }
            .getOrNull() ?: UploadSchedule.NIGHTLY
        val zoneId = runCatching { uploadPrefs.zoneIdFlow.first() }
            .getOrNull().orEmpty()
        outputFormat = runCatching { uploadPrefs.outputFormatFlow.first() }
            .getOrNull() ?: UploadOutputFormat.GEOJSON
        zone = try {
            if (zoneId.isNotBlank()) ZoneId.of(zoneId) else ZoneId.of("Asia/Tokyo")
        } catch (_: Throwable) {
            ZoneId.of("Asia/Tokyo")
        }

        val forceFullScan = inputData.getBoolean(KEY_FORCE_FULL_SCAN, false)

        // When invoked by scheduler (non-force), respect schedule and skip when not NIGHTLY.
        if (!forceFullScan && schedule != UploadSchedule.NIGHTLY) {
            runCatching {
                val msg = "Midnight export skipped: schedule=${schedule.wire}"
                drivePrefs.setBackupStatus(msg)
            }
            // Still schedule next run so that changes to schedule are picked up.
            scheduleNext(applicationContext)
            return Result.success()
        }

        val today = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toLocalDate()
        val todayEpochDay = today.toEpochDay()

        // Record a brief summary at worker start for debugging / UI status.
        runCatching {
            val exportedCount = StorageService.exportedDayCount(applicationContext)
            val oldest = StorageService.oldestNotUploadedDay(applicationContext)
            val msg = buildString {
                append("Backup worker start: today=")
                append(today)
                append(", exported_days.count=")
                append(exportedCount)
                append(", oldestNotUploaded=")
                append(oldest?.epochDay ?: "null")
                append(", forceFullScan=")
                append(forceFullScan)
                append(", format=")
                append(outputFormat.wire)
            }
            drivePrefs.setBackupStatus(msg)
        }

        if (forceFullScan) {
            runFullScan(today, todayEpochDay)
        } else {
            runBacklog(today, todayEpochDay)
        }

        // After processing, schedule next run at next midnight.
        scheduleNext(applicationContext)
        return Result.success()
    }

    private suspend fun runBacklog(today: LocalDate, todayEpochDay: Long) {
        var first = StorageService.oldestNotUploadedDay(applicationContext)
        if (first == null) {
            val backMaxDays = 365L
            for (off in backMaxDays downTo 1L) {
                val d = today.minusDays(off).toEpochDay()
                if (d < todayEpochDay) {
                    StorageService.ensureExportedDay(applicationContext, d)
                }
            }
            first = StorageService.oldestNotUploadedDay(applicationContext)
        }

        var current = first
        while (current != null && current.epochDay < todayEpochDay) {
            processOneDay(current.epochDay)
            current = StorageService.nextNotUploadedDayAfter(applicationContext, current.epochDay)
        }
    }

    private suspend fun runFullScan(today: LocalDate, todayEpochDay: Long) {
        val minMillis = StorageService.firstSampleTimeMillis(applicationContext) ?: return
        val maxMillis = StorageService.lastSampleTimeMillis(applicationContext) ?: return
        val firstDate = Instant.ofEpochMilli(minMillis).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(maxMillis).atZone(zone).toLocalDate()

        var day = firstDate.toEpochDay()
        val lastEpochDay = minOf(lastDate.toEpochDay(), todayEpochDay - 1)
        while (day <= lastEpochDay) {
            processOneDay(day)
            day++
        }
    }

    private suspend fun processOneDay(epochDay: Long) {
        val localDate = LocalDate.ofEpochDay(epochDay)
        val startMillis = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val baseName = "glp-${dateFmt.format(localDate)}"

        try {
            // Ensure ExportedDay row exists for this day.
            StorageService.ensureExportedDay(applicationContext, epochDay)

            // Fetch one-day records from storageservice DB via StorageService.
            val records = StorageService.getLocationsBetween(
                ctx = applicationContext,
                from = startMillis,
                to = endMillis,
                softLimit = 1_000_000
            )

              // Generate local export as ZIP (GeoJSON or GPX).
              val exported: Uri? =
                  when (outputFormat) {
                      UploadOutputFormat.GEOJSON -> GeoJsonExporter.exportToDownloads(
                          context = applicationContext,
                          records = records,
                          baseName = baseName,
                          compressAsZip = true
                      )
                      UploadOutputFormat.GPX -> GpxExporter.exportToDownloads(
                          context = applicationContext,
                          records = records,
                          baseName = baseName,
                          compressAsZip = true
                      )
                  }

            // Mark local export as succeeded (even for empty day).
            StorageService.markExportedLocal(applicationContext, epochDay)

            // Upload to Drive only when settings are configured.
            val appPrefs = AppPrefs.snapshot(applicationContext)
            val uiFolder = runCatching { drivePrefs.folderIdFlow.first() }.getOrNull().orEmpty()
            val effectiveFolderId = uiFolder.ifBlank { appPrefs.folderId }

            val engineOk = (appPrefs.engine == UploadEngine.KOTLIN)
            val folderOk = effectiveFolderId.isNotBlank()
            val uploader = if (engineOk && folderOk) {
                UploaderFactory.create(applicationContext, appPrefs.engine)
            } else null

            var uploadSucceeded = false
            var lastError: String? = null

            if (exported == null) {
                lastError = "No file exported"
                StorageService.markExportError(applicationContext, epochDay, lastError)
            } else if (uploader == null) {
                lastError = when {
                    !engineOk -> "Upload not configured (engine=${appPrefs.engine})"
                    !folderOk -> "Upload not configured (folderId missing)"
                    else -> "Uploader disabled"
                }
                StorageService.markExportError(applicationContext, epochDay, lastError)
            } else {
                when (val up = uploader.upload(exported, effectiveFolderId, "$baseName.zip")) {
                    is UploadResult.Success -> {
                        uploadSucceeded = true
                        StorageService.markUploaded(applicationContext, epochDay, null)
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
                        StorageService.markExportError(applicationContext, epochDay, lastError)
                    }
                }
            }

            val progress = buildString {
                append("Backup day ")
                append(localDate)
                append(" (")
                append(baseName)
                append(".zip): local=")
                append(if (exported != null) "OK" else "FAIL")
                append(", folder=")
                append(if (folderOk) effectiveFolderId else "none")
                append(", upload=")
                append(
                    when {
                        exported == null -> "SKIP(no file)"
                        uploader == null -> "SKIP(config)"
                        uploadSucceeded  -> "OK"
                        !lastError.isNullOrBlank() -> "FAIL(${lastError.take(120)})"
                        else -> "FAIL"
                    }
                )
            }
            runCatching { drivePrefs.setBackupStatus(progress) }

            // Always delete ZIP to avoid filling local storage.
            exported?.let { safeDelete(it) }

            // Only when upload succeeds and there are records, delete that day's records from DB.
            if (uploadSucceeded && records.isNotEmpty()) {
                try {
                    StorageService.deleteLocations(applicationContext, records)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to delete uploaded records, will retry next time", e)
                }
            }
        } catch (t: Throwable) {
            val err = "Exception ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            runCatching {
                StorageService.markExportError(applicationContext, epochDay, err)
                val progress = "Backup day $localDate ($baseName.zip): EXCEPTION($err)"
                drivePrefs.setBackupStatus(progress)
            }
        }
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
}
