package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for exporting today's logs (00:00 to now) into a single ZIP file.
 *
 * - Creates a ZIP under Downloads.
 * - Optionally uploads to Drive and deletes the local ZIP.
 */
enum class TodayPreviewMode {
    /** Upload to Drive and always delete the local ZIP; Room data is not deleted. */
    UPLOAD_AND_DELETE_LOCAL,

    /** Do not upload; keep ZIP in Downloads; Room data is not deleted. */
    SAVE_TO_DOWNLOADS_ONLY
}

class ManualExportViewModel(
    private val appContext: Context
) : ViewModel() {

    /**
     * Behavior:
     * - Target: all records from today 00:00 to now, bundled into one ZIP.
     * - File name: glp-YYYYMMDD-HHmmss.zip (based on execution time).
     * - Modes:
     *   - UPLOAD_AND_DELETE_LOCAL:
     *       - Up to 5 upload attempts. ZIP is always deleted regardless of success/failure.
     *       - Room data is never deleted.
     *   - SAVE_TO_DOWNLOADS_ONLY:
     *       - Save ZIP in Downloads. ZIP is kept. Room data is never deleted.
     */
    fun backupToday(mode: TodayPreviewMode) {
        viewModelScope.launch(Dispatchers.IO) {

            val zone = ZoneId.of("Asia/Tokyo")
            val now = ZonedDateTime.now(zone)
            val today0 = now.truncatedTo(ChronoUnit.DAYS)
            val startMillis = today0.toInstant().toEpochMilli()
            val endMillis = now.toInstant().toEpochMilli()

            // Prefer range query via StorageService; fall back to manual filtering
            val todays = try {
                StorageService.getLocationsBetween(appContext, startMillis, endMillis)
            } catch (_: Throwable) {
                StorageService.getAllLocations(appContext).filter { rec ->
                    val candidates = arrayOf(
                        "timestampMillis", "timeMillis", "createdAtMillis",
                        "timestamp", "createdAt", "recordedAt", "epochMillis"
                    )
                    val ms = candidates.firstNotNullOfOrNull { name ->
                        runCatching {
                            val f = rec.javaClass.getDeclaredField(name)
                            f.isAccessible = true
                            (f.get(rec) as? Number)?.toLong()
                        }.getOrNull()
                    } ?: 0L
                    ms in startMillis..endMillis
                }
            }

            if (todays.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "No data for today.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val baseName = "glp-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now)
            var outUri: Uri? = null
            try {
                outUri = GeoJsonExporter.exportToDownloads(
                    context = appContext,
                    records = todays,
                    baseName = baseName,
                    compressAsZip = true
                )
                if (outUri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Failed to create ZIP.", Toast.LENGTH_SHORT)
                            .show()
                    }
                    return@launch
                }

                when (mode) {
                    TodayPreviewMode.SAVE_TO_DOWNLOADS_ONLY -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                "Saved to Downloads as $baseName.zip (Room data kept).",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    TodayPreviewMode.UPLOAD_AND_DELETE_LOCAL -> {
                        val tokenProvider = CredentialManagerAuth.get(appContext)
                        val token = tokenProvider.getAccessToken()
                        if (token == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    appContext,
                                    "Drive authorization is missing. Please grant permission in settings.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            runCatching { appContext.contentResolver.delete(outUri, null, null) }
                            return@launch
                        }

                        val prefs = AppPrefs.snapshot(appContext)
                        val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
                        val uploader = UploaderFactory.create(
                            appContext,
                            prefs.engine,
                            tokenProvider = tokenProvider
                        )

                        if (uploader == null || folderId.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    appContext,
                                    "Upload settings are incomplete (check folder and auth).",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            // Delete ZIP; keep Room data
                            runCatching { appContext.contentResolver.delete(outUri, null, null) }
                            return@launch
                        }

                        var success = false
                        for (attempt in 0 until 5) {
                            when (val result = uploader.upload(outUri, folderId, null)) {
                                is UploadResult.Success -> {
                                    // Preview mode: Room data is not deleted
                                    success = true
                                    break
                                }

                                is UploadResult.Failure -> {
                                    if (attempt < 4) {
                                        // Exponential backoff: 15s, 30s, 60s, 120s
                                        delay(15_000L * (1 shl attempt))
                                    }
                                }
                            }
                        }

                        // Delete ZIP regardless of success/failure; keep Room data
                        runCatching { appContext.contentResolver.delete(outUri, null, null) }

                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(
                                    appContext,
                                    "Upload succeeded. Local ZIP deleted; Room data kept.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    appContext,
                                    "Upload failed after retries. Local ZIP deleted; Room data kept.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            } finally {
                // For SAVE_TO_DOWNLOADS_ONLY we intentionally keep ZIP; no cleanup needed here
                if (outUri != null && mode == TodayPreviewMode.SAVE_TO_DOWNLOADS_ONLY) {
                    // No-op: keep ZIP
                }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    return ManualExportViewModel(context.applicationContext) as T
                }
            }
    }
}

