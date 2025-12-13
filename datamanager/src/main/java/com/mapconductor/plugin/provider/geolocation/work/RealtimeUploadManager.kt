package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.prefs.UploadPrefsRepository
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Realtime upload manager that watches new LocationSample rows and uploads them
 * as GeoJSON based on UploadPrefs schedule and interval.
 *
 * Responsibilities:
 * - Observe StorageService.latestFlow to detect new samples.
 * - Apply interval rules (sample-triggered cooldown).
 * - Generate GeoJSON into cacheDir and upload via UploaderFactory.
 * - On success, delete the uploaded LocationSample rows from Room.
 *
 * This object is started from App.onCreate() and runs in process scope.
 */
object RealtimeUploadManager {

    private const val TAG = "RealtimeUploadManager"

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var schedule: UploadSchedule = UploadSchedule.NIGHTLY
    @Volatile private var intervalSec: Int = 0
    @Volatile private var zoneId: String = "Asia/Tokyo"
    @Volatile private var gpsIntervalSec: Int = 30
    @Volatile private var drIntervalSec: Int = 5

    @Volatile private var lastSampleTimeMillis: Long = 0L
    @Volatile private var lastUploadStartMillis: Long = 0L

    private val uploadMutex = Mutex()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Log.d(TAG, "start")

        val uploadPrefs = UploadPrefsRepository(appContext)
        val drivePrefs = DrivePrefsRepository(appContext)

        // Settings watchers.
        scope.launch {
            uploadPrefs.scheduleFlow.collect { s ->
                schedule = s
            }
        }
        scope.launch {
            uploadPrefs.intervalSecFlow.collect { v ->
                intervalSec = when {
                    v < 0      -> 0
                    v > 86_400 -> 86_400
                    else       -> v
                }
            }
        }
        scope.launch {
            uploadPrefs.zoneIdFlow.collect { z ->
                zoneId = if (z.isNotBlank()) z else "Asia/Tokyo"
            }
        }
        scope.launch {
            SettingsRepository.intervalSecFlow(appContext)
                .collect { sec -> gpsIntervalSec = sec }
        }
        scope.launch {
            SettingsRepository.drIntervalSecFlow(appContext)
                .collect { sec -> drIntervalSec = sec }
        }

        // Sample watcher.
        scope.launch {
            try {
                StorageService.latestFlow(appContext, limit = 1)
                    .collect { list ->
                        val latest = list.firstOrNull()?.timeMillis ?: 0L
                        if (latest <= 0L) return@collect
                        if (latest <= lastSampleTimeMillis) return@collect
                        lastSampleTimeMillis = latest

                        // Only react when realtime schedule is enabled.
                        if (schedule != UploadSchedule.REALTIME) return@collect

                        handleNewSample(appContext)
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "latestFlow collector failed", t)
            }
        }

        // Warm up Drive prefs to avoid first-use latency in upload path.
        scope.launch {
            runCatching { drivePrefs.folderIdFlow.first() }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        Log.d(TAG, "stop")
        scope.cancel()
    }

    private suspend fun handleNewSample(appContext: Context) {
        val now = System.currentTimeMillis()
        val effectiveIntervalSec = computeEffectiveIntervalSec()

        // When effectiveIntervalSec is 0, treat as "upload on every new sample".
        if (effectiveIntervalSec == 0) {
            maybeUpload(appContext, now)
            return
        }

        val last = lastUploadStartMillis
        if (last <= 0L || now - last >= effectiveIntervalSec * 1000L) {
            maybeUpload(appContext, now)
        }
    }

    private fun computeEffectiveIntervalSec(): Int {
        val base = intervalSec
        if (base <= 0) return 0

        val drEnabled = drIntervalSec > 0
        val refInterval = if (drEnabled) drIntervalSec else gpsIntervalSec
        return if (refInterval > 0 && refInterval == base) 0 else base
    }

    private suspend fun maybeUpload(appContext: Context, nowMillis: Long) {
        uploadMutex.withLock {
            lastUploadStartMillis = nowMillis

            val records = StorageService.getAllLocations(appContext)
            if (records.isEmpty()) {
                Log.d(TAG, "maybeUpload: no records in DB")
                return
            }

            val latestMillis = records.last().timeMillis
            if (latestMillis <= 0L) {
                Log.d(TAG, "maybeUpload: latest sample has invalid timeMillis")
                return
            }

            val zone = try {
                ZoneId.of(zoneId)
            } catch (_: Throwable) {
                ZoneId.of("Asia/Tokyo")
            }
            val dt = Instant.ofEpochMilli(latestMillis).atZone(zone)
            val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            val baseName = fmt.format(dt)

            val json = GeoJsonExporter.toGeoJson(records).toByteArray(Charsets.UTF_8)
            val file = File(appContext.cacheDir, "$baseName.json")
            try {
                FileOutputStream(file).use { os ->
                    os.write(json)
                    os.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to write GeoJSON to cache file", t)
                runCatching { file.delete() }
                return
            }

            val uri: Uri = Uri.fromFile(file)

            // Resolve Drive folder and uploader.
            val drivePrefs = DrivePrefsRepository(appContext)
            val appPrefs = AppPrefs.snapshot(appContext)
            val uiFolder = runCatching { drivePrefs.folderIdFlow.first() }
                .getOrNull().orEmpty()
            val effectiveFolderId = uiFolder.ifBlank { appPrefs.folderId }

            val engineOk = (appPrefs.engine == UploadEngine.KOTLIN)
            val folderOk = effectiveFolderId.isNotBlank()
            val uploader = if (engineOk && folderOk) {
                UploaderFactory.create(appContext, appPrefs.engine)
            } else null

            var uploadSucceeded = false

            try {
                if (uploader == null) {
                    Log.w(
                        TAG,
                        "maybeUpload: uploader is null (engine=${
                            appPrefs.engine
                        }, folderOk=$folderOk)"
                    )
                } else {
                    when (val up = uploader.upload(uri, effectiveFolderId, "$baseName.json")) {
                        is UploadResult.Success -> {
                            uploadSucceeded = true
                            Log.d(TAG, "Realtime upload OK id=${up.id} name=${up.name}")
                        }
                        is UploadResult.Failure -> {
                            Log.w(
                                TAG,
                                "Realtime upload failed code=${up.code} msg=${up.message} body=${
                                    up.body.take(
                                        160
                                    )
                                }"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Realtime upload threw exception", t)
            } finally {
                runCatching { file.delete() }
            }

            if (uploadSucceeded) {
                try {
                    StorageService.deleteLocations(appContext, records)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete uploaded records", t)
                }
            }
        }
    }
}
