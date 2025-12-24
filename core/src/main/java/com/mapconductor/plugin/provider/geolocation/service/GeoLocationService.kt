package com.mapconductor.plugin.provider.geolocation.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoning
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningFactory
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.GpsFix
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.PredictedPoint
import com.mapconductor.plugin.provider.geolocation.gps.FusedLocationGpsEngine
import com.mapconductor.plugin.provider.geolocation.gps.GpsLocationEngine
import com.mapconductor.plugin.provider.geolocation.gps.GpsObservation
import com.mapconductor.plugin.provider.geolocation.util.BatteryStatusReader
import com.mapconductor.plugin.provider.geolocation.util.HeadingSensor
import com.mapconductor.plugin.provider.geolocation.debug.DrDebugState
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import androidx.core.app.NotificationCompat
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.prefs.DrMode
import com.mapconductor.plugin.provider.storageservice.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class GeoLocationService : Service() {

    companion object {
        private const val TAG = "GeoLocationService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"

        /** Update GPS sampling interval (milliseconds). */
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS = "EXTRA_UPDATE_MS"

        /** Update DR prediction interval (seconds). */
        const val ACTION_UPDATE_DR_INTERVAL = "ACTION_UPDATE_DR_INTERVAL"
        const val EXTRA_DR_INTERVAL_SEC = "EXTRA_DR_INTERVAL_SEC"

        // Maximum number of recent GPS samples kept for DR static clamping.
        private const val GPS_HISTORY_SIZE = 8

        // Static-mode GPS outlier thresholds.
        private const val GPS_STATIC_MAX_ACCURACY_M = 80f
        private const val GPS_STATIC_MAX_STEP_METERS = 25.0
        private const val GPS_STATIC_MAX_SPEED_MPS = 1.5
        private const val GPS_STATIC_MAX_ACCEL_MPS2 = 3.0
    }

    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var gpsEngine: GpsLocationEngine
    private lateinit var headingSensor: HeadingSensor
    private var dr: DeadReckoning? = null

    @Volatile private var updateIntervalMs: Long = 30_000L
    @Volatile private var drMode: DrMode = DrMode.Prediction
    private val isRunning = AtomicBoolean(false)
    @Volatile private var lastFixMillis: Long? = null // Most recent GPS fix timestamp (used as DR base).

    // DR ticker for real-time prediction loop.
    private var drTickerJob: Job? = null
    @Volatile private var drIntervalSec: Int = 5  // Default interval in seconds.

    // Duplicate insertion suppression for GPS and DR.
    @Volatile private var lastInsertMillis: Long = 0L
    @Volatile private var lastInsertSig: Long = 0L
    private val insertLock = Any()
    @Volatile private var lastDrInsertMillis: Long = 0L
    @Volatile private var lastDrInsertSig: Long = 0L
    private val drInsertLock = Any()

    // Last position and course used to estimate movement direction.
    @Volatile private var lastCourseDeg: Double = Double.NaN
    @Volatile private var lastCourseLat: Double? = null
    @Volatile private var lastCourseLon: Double? = null

    // Smoothed GPS position used as DR anchor ("GPS hold value").
    @Volatile private var lastGpsHoldLat: Double? = null
    @Volatile private var lastGpsHoldLon: Double? = null

    // Last raw GPS receive value ("GPS receive value") kept for hold computation.
    @Volatile private var lastGpsRecvLat: Double? = null
    @Volatile private var lastGpsRecvLon: Double? = null
    @Volatile private var lastGpsRecvTimeMillis: Long? = null
    @Volatile private var lastGpsRecvAccM: Float? = null

    // Recent raw GPS samples used for DR clamping when static.
    private data class GpsHistorySample(
        val lat: Double,
        val lon: Double,
        val timeMillis: Long,
        val accuracyM: Float,
        val speedMps: Double
    )

    private val gpsHistoryLock = Any()
    private val gpsHistory: ArrayDeque<GpsHistorySample> = ArrayDeque()

    fun getUpdateIntervalMs(): Long = updateIntervalMs
    fun isLocationRunning(): Boolean = isRunning.get()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        gpsEngine = FusedLocationGpsEngine(applicationContext, mainLooper).also { engine ->
            engine.setListener(object : GpsLocationEngine.Listener {
                override fun onGpsObservation(observation: GpsObservation) {
                    handleObservation(observation)
                }
            })
        }
        headingSensor = HeadingSensor(applicationContext).also { it.start() }
        // Configure DeadReckoning with relaxed static detection so that
        // desk / slow-move scenarios (hand-held use at almost the same
        // position) are treated as static, and use a stronger velocityGain
        // so DR speed follows GPS more closely.
        val drConfig = DeadReckoningConfig(
            staticAccelVarThreshold = 0.10f,
            staticGyroVarThreshold = 0.01f * 0.01f,
            velocityGain = 0.4f,
            windowSize = 96
        )
        dr = DeadReckoningFactory.create(applicationContext, drConfig).also { it.start() }
        ensureChannel()
        // GPS sampling interval is always synced from SettingsRepository.intervalSecFlow().
        serviceScope.launch {
            SettingsRepository.intervalSecFlow(applicationContext)
                .distinctUntilChanged()
                .collect { sec ->
                    val ms = max(1_000L, sec * 1_000L)
                    if (ms != updateIntervalMs) {
                        Log.d(TAG, "interval changed: ${updateIntervalMs}ms -> ${ms}ms")
                        updateIntervalMs = ms
                        if (isRunning.get()) {
                            restartLocationUpdates()
                        }
                    }
                }
        }
        // DR prediction interval is also synced from a Flow so both Save&Apply and manual start use the same value.
        serviceScope.launch {
            SettingsRepository.drIntervalSecFlow(applicationContext)
                .distinctUntilChanged()
                .collect { sec ->
                    Log.d(TAG, "dr interval changed: $drIntervalSec -> $sec")
                    applyDrInterval(sec)
                }
        }
        // DR mode (prediction vs completion) is also synced from a Flow.
        serviceScope.launch {
            SettingsRepository.drModeFlow(applicationContext)
                .distinctUntilChanged()
                .collect { mode ->
                    applyDrMode(mode)
                }
        }
        // ---- end of initial setup ----
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        stopDrTicker()
        try { gpsEngine.stop() } catch (t: Throwable) { Log.w(TAG, "gpsEngine.stop()", t) }
        try { headingSensor.stop() } catch (t: Throwable) { Log.w(TAG, "headingSensor.stop()", t) }
        try { dr?.stop() } catch (t: Throwable) { Log.w(TAG, "dr.stop()", t) }
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startLocation()
            ACTION_STOP  -> stopLocation()

            ACTION_UPDATE_INTERVAL -> {
                val ms = intent.getLongExtra(EXTRA_UPDATE_MS, updateIntervalMs)
                updateIntervalMs = max(5000L, ms) // Minimum GPS interval is 5 seconds.
                Log.d(TAG, "ACTION_UPDATE_INTERVAL: updateIntervalMs=$updateIntervalMs")
                if (isRunning.get()) {
                    serviceScope.launch { restartLocationUpdates() }
                }
            }

            ACTION_UPDATE_DR_INTERVAL -> {
                val sec = intent.getIntExtra(EXTRA_DR_INTERVAL_SEC, drIntervalSec)
                Log.d(TAG, "ACTION_UPDATE_DR_INTERVAL: sec=$sec")
                applyDrInterval(sec)
            }
        }
        return START_STICKY
    }

    private fun startLocation() {
        if (isRunning.getAndSet(true)) return
        Log.d(TAG, "startLocation")
        startForeground(NOTIFICATION_ID, buildNotification())
        gpsEngine.updateInterval(updateIntervalMs)
        gpsEngine.start()
        updateDrTicker()
    }

    private fun stopLocation() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "stopLocation")

        // Remove foreground service state.
        stopForeground(STOP_FOREGROUND_DETACH)

        // Stop location updates.
        try { gpsEngine.stop() } catch (t: Throwable) {
            Log.w(TAG, "gpsEngine.stop()", t)
        }

        // Stop DR ticker.
        stopDrTicker()
        // Reset simple DR anchor state so that the next start does not
        // accidentally reuse stale GPS information.
        lastFixMillis = null

        // Important:
        // Start/Stop button checks whether it can bind to the service.
        // To reflect stopped state correctly, fully stop the service here.
        stopSelf()
    }

    // ------------------------
    //   GPS update control
    // ------------------------
    private suspend fun restartLocationUpdates() {
        Log.d(TAG, "restartLocationUpdates: interval=${updateIntervalMs}ms")
        gpsEngine.updateInterval(updateIntervalMs)
    }

    private fun handleObservation(observation: GpsObservation) {
        // Even if the foreground notification is temporarily hidden by the user,
        // it should be shown again at the next GPS update.
        ensureNotificationVisible()

        val now = observation.timestampMillis
        val lastFix = lastFixMillis

        val headingDeg: Float = headingSensor.headingTrueDeg() ?: 0f
        val speedMps: Float = max(0f, observation.speedMps.takeIf { !it.isNaN() } ?: 0f)

        // --- Estimate course (direction of travel) ---
        val prevLat = lastCourseLat
        val prevLon = lastCourseLon

        val rawBearing = observation.bearingDeg ?: Float.NaN
        val hasBearing = observation.hasBearing

        val courseDeg: Double = when {
            // 1) If the provider gives bearing, use it preferentially.
            hasBearing && !rawBearing.isNaN() -> {
                val normalized = ((rawBearing % 360f) + 360f) % 360f
                lastCourseDeg = normalized.toDouble()
                normalized.toDouble()
            }

            // 2) If bearing is not available, estimate direction from the last position.
            prevLat != null && prevLon != null -> {
                val prev = android.location.Location("gps").apply {
                    latitude = prevLat
                    longitude = prevLon
                }
                val current = android.location.Location("gps").apply {
                    latitude = observation.lat
                    longitude = observation.lon
                }
                val distance = prev.distanceTo(current) // [m]
                if (distance >= 0.5f) {                  // Threshold is small (0.5 m) to pick up small movements.
                    val b = prev.bearingTo(current)
                    if (!b.isNaN()) {
                        val normalized = ((b % 360f) + 360f) % 360f
                        lastCourseDeg = normalized.toDouble()
                    }
                }

                if (lastCourseDeg.isNaN()) {
                    Double.NaN   // No course decided yet.
                } else {
                    lastCourseDeg
                }
            }

            // 3) Otherwise keep the previous course value.
            !lastCourseDeg.isNaN() -> lastCourseDeg

            // 4) If there is no information (first sample), keep NaN.
            else -> Double.NaN
        }

        // Update last position for the next course calculation.
        lastCourseLat = observation.lat
        lastCourseLon = observation.lon

        // ------------------------
        //   GPS hold value for DR
        // ------------------------
        val recvLat = observation.lat
        val recvLon = observation.lon
        val recvAcc = observation.accuracyM

        val prevHoldLat = lastGpsHoldLat
        val prevHoldLon = lastGpsHoldLon
        val prevRecvLat = lastGpsRecvLat
        val prevRecvLon = lastGpsRecvLon
        val prevRecvTime = lastGpsRecvTimeMillis
        val prevRecvAcc = lastGpsRecvAccM

        // Decide the base point for hold calculation.
        val baseLat: Double?
        val baseLon: Double?
        if (prevHoldLat != null && prevHoldLon != null) {
            // Normal case: use previous GPS hold value.
            baseLat = prevHoldLat
            baseLon = prevHoldLon
        } else {
            // Fallback: use previous GPS receive value when available and valid.
            val canUsePrevRecv =
                prevRecvLat != null &&
                    prevRecvLon != null &&
                    prevRecvTime != null &&
                    prevRecvAcc != null &&
                    !prevRecvAcc.isNaN() &&
                    prevRecvAcc < 20f &&
                    (now - prevRecvTime) < (updateIntervalMs * 4L)

            if (canUsePrevRecv) {
                baseLat = prevRecvLat
                baseLon = prevRecvLon
            } else {
                baseLat = null
                baseLon = null
            }
        }

        val (holdLat, holdLon) = if (baseLat == null || baseLon == null) {
            // No usable previous hold/receive value: reset filter and use the current receive value.
            recvLat to recvLon
        } else {
            // Compute blend factor based on accuracy and speed so that
            // high-speed motion stays responsive even under poor accuracy.
            val effectiveSpeedMps = if (speedMps < 1.0f) 1.0 else speedMps.toDouble()
            val weight: Double = recvAcc
                .takeIf { it > 0f && !it.isNaN() }
                ?.let { ((10.0 * effectiveSpeedMps) / it.toDouble()).coerceAtMost(1.0) }
                ?: 1.0

            if (weight >= 1.0) {
                // When accuracy and speed imply a strong GPS signal, hold and receive are treated as identical.
                recvLat to recvLon
            } else if (weight <= 0.0) {
                baseLat to baseLon
            } else {
                val lat = baseLat + weight * (recvLat - baseLat)
                val lon = baseLon + weight * (recvLon - baseLon)
                lat to lon
            }
        }

        // Persist hold and receive values for the next update.
        lastGpsHoldLat = holdLat
        lastGpsHoldLon = holdLon
        lastGpsRecvLat = recvLat
        lastGpsRecvLon = recvLon
        lastGpsRecvTimeMillis = now
        lastGpsRecvAccM = recvAcc

        // Record this raw GPS sample for DR static clamping.
        val historyEntry = GpsHistorySample(
            lat = recvLat,
            lon = recvLon,
            timeMillis = now,
            accuracyM = recvAcc,
            speedMps = speedMps.toDouble()
        )
        synchronized(gpsHistoryLock) {
            gpsHistory.addLast(historyEntry)
            while (gpsHistory.size > GPS_HISTORY_SIZE) {
                gpsHistory.removeFirst()
            }
        }

          val used: Int? = observation.gnssUsed
          val total: Int? = observation.gnssTotal
          val cn0: Double? = observation.cn0Mean

        // 1) Insert GPS sample.
        run {
            val bat = BatteryStatusReader.read(applicationContext)
            val sample = LocationSample(
                id = 0,
                timeMillis = now,
                lat = observation.lat,
                lon = observation.lon,
                accuracy = observation.accuracyM,
                provider = "gps",
                headingDeg = headingDeg.toDouble(),
                courseDeg = courseDeg,
                speedMps = speedMps.toDouble(),
                gnssUsed = used ?: -1,
                gnssTotal = total ?: -1,
                cn0 = cn0 ?: 0.0,
                batteryPercent = bat.percent,
                isCharging = bat.isCharging
            )
            val sig = sig(
                sample.lat, sample.lon, sample.accuracy,
                sample.provider,
                sample.headingDeg,
                courseDeg,                    // GPS uses this computed courseDeg.
                sample.speedMps,
                sample.gnssUsed, sample.gnssTotal, sample.cn0,
                sample.batteryPercent, sample.isCharging
            )
            var skip = false
            synchronized(insertLock) {
                if (sig == lastInsertSig && now - lastInsertMillis <= min(1000L, updateIntervalMs / 2)) {
                    skip = true
                } else {
                    lastInsertSig = sig
                    lastInsertMillis = now
                }
            }
              if (!skip) {
                  serviceScope.launch(Dispatchers.IO) {
                      Log.d(
                          "DB/TRACE",
                          "before-insert provider=gps t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}"
                      )
                      try {
                          StorageService.insertLocation(applicationContext, sample)
                          Log.d(
                              "DB/TRACE",
                              "after-insert ok provider=gps t=${sample.timeMillis}"
                          )
                      } catch (t: Throwable) {
                          Log.e(
                              "DB/TRACE",
                              "insert failed provider=gps t=${sample.timeMillis}",
                              t
                          )
                      }
                  }
            } else {
                Log.d("DB/TRACE", "gps insert skipped (dup guard)")
            }
        }

        // 2) Notify DR of the fix and optionally backfill the previous segment.
        if (drIntervalSec > 0) {
            val startMillis = lastFixMillis
            val endMillis = now
            val modeSnapshot = drMode
            if (modeSnapshot == DrMode.Completion && startMillis != null && endMillis > startMillis) {
                scheduleDrBackfill(
                    startMillis = startMillis,
                    endMillis = endMillis,
                    endHoldLat = holdLat,
                    endHoldLon = holdLon,
                    endAccuracyM = observation.accuracyM,
                    endSpeedMps = speedMps
                )
            }
            lastFixMillis = endMillis
            serviceScope.launch(Dispatchers.Default) {
                val engine = dr
                if (engine != null) {
                    try {
                        engine.submitGpsFix(
                            GpsFix(
                                timestampMillis = endMillis,
                                lat = holdLat,
                                lon = holdLon,
                                accuracyM = observation.accuracyM,
                                speedMps = speedMps.takeIf { it > 0f }
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "dr.submitGpsFix()", t)
                    }
                }
            }
        }
    }

    // ------------------------
    //   DR ticker (real time)
    // ------------------------
    private fun startDrTicker() {
        stopDrTicker()
        val d = dr ?: return
        if (drIntervalSec <= 0) {
            Log.d(TAG, "startDrTicker: DR disabled (intervalSec=$drIntervalSec)")
            return
        }
        Log.d(TAG, "startDrTicker interval=${drIntervalSec}s")
        drTickerJob = serviceScope.launch(Dispatchers.IO) {
            while (isRunning.get()) {
                // Even if there is no GPS update for a while, at DR interval
                // we re-show the foreground notification.
                ensureNotificationVisible()

                val base = lastFixMillis
                try {
                    if (base != null) {
                        val now = System.currentTimeMillis()
                        val pts: List<PredictedPoint> = try {
                            d.predict(fromMillis = base, toMillis = now)
                        } catch (e: Throwable) {
                            Log.w(TAG, "dr.predict(ticker) from=$base to=$now", e)
                            emptyList()
                        }
                        val p = pts.lastOrNull()
                        if (p != null) {
                            // Update DR debug state so that the app-side
                            // overlay can display the current isLikelyStatic()
                            // flag from the engine.
                            val staticFlag =
                                try {
                                    d.isLikelyStatic()
                                } catch (t: Throwable) {
                                    Log.w(TAG, "dr.isLikelyStatic()", t)
                                    false
                                }
                            DrDebugState.update(isStatic = staticFlag, lastUpdateMillis = now)

                            val bat = BatteryStatusReader.read(applicationContext)

                            // Decide heading / course for DR (real-time).
                            val headingForDr = headingSensor.headingTrueDeg()?.toDouble() ?: 0.0
                            val courseForDr = if (!lastCourseDeg.isNaN()) {
                                lastCourseDeg
                            } else {
                                headingForDr
                            }

                            val sample = LocationSample(
                                id = 0,
                                timeMillis = now,
                                lat = p.lat,
                                lon = p.lon,
                                accuracy = (p.accuracyM ?: 0f),
                                provider = "dead_reckoning",
                                headingDeg = headingForDr,
                                courseDeg = courseForDr,
                                speedMps = (p.speedMps ?: Float.NaN).toDouble(),
                                gnssUsed = -1,
                                gnssTotal = -1,
                                cn0 = 0.0,
                                batteryPercent = bat.percent,
                                isCharging = bat.isCharging
                            )
                            // Duplicate insertion guard for DR.
                            val sig = sig(
                                sample.lat, sample.lon, sample.accuracy,
                                sample.provider,
                                sample.headingDeg,
                                sample.courseDeg ?: 0.0,   // Not nullable, but keep 0.0 as fallback.
                                sample.speedMps,
                                sample.gnssUsed, sample.gnssTotal, sample.cn0,
                                sample.batteryPercent, sample.isCharging
                            )
                            var skip = false
                            synchronized(drInsertLock) {
                                if (sig == lastDrInsertSig && now - lastDrInsertMillis <= (drIntervalSec * 500L)) {
                                    skip = true
                                } else {
                                    lastDrInsertSig = sig
                                    lastDrInsertMillis = now
                                }
                            }
                            if (!skip) {
                                val (adjLat, adjLon) =
                                    adjustDrSample(
                                        lat = sample.lat,
                                        lon = sample.lon,
                                        speedMps = sample.speedMps,
                                    )
                                val finalSample =
                                    if (adjLat != sample.lat || adjLon != sample.lon) {
                                        sample.copy(lat = adjLat, lon = adjLon)
                                    } else {
                                        sample
                                    }
                                Log.d(
                                    "DB/TRACE",
                                    "before-insert provider=dead_reckoning(ticker) t=${finalSample.timeMillis} lat=${finalSample.lat} lon=${finalSample.lon}"
                                )
                                try {
                                    StorageService.insertLocation(applicationContext, finalSample)
                                    Log.d(
                                        "DB/TRACE",
                                        "after-insert ok provider=dead_reckoning(ticker) t=${finalSample.timeMillis}"
                                    )
                                } catch (e: Throwable) {
                                    Log.e(
                                        "DB/TRACE",
                                        "insert failed provider=dead_reckoning(ticker)",
                                        e
                                    )
                                }
                            } else {
                                Log.d("DB/TRACE", "dr insert skipped (dup guard)")
                            }
                        }
                    } else {
                        Log.d(TAG, "drTicker: base(lastFixMillis) = null (waiting first GPS fix)")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "drTicker loop error", t)
                }
                delay((drIntervalSec * 1000L).coerceAtLeast(1000L))
            }
            Log.d(TAG, "drTicker: loop end")
        }
    }

    private fun stopDrTicker() {
        Log.d(TAG, "stopDrTicker")
        drTickerJob?.cancel()
        drTickerJob = null
    }

    private fun applyDrInterval(sec: Int) {
        val clamped = if (sec <= 0) 0 else max(1, sec)
        Log.d(TAG, "applyDrInterval: $drIntervalSec -> $clamped")
        drIntervalSec = clamped
        updateDrTicker()
    }

    private fun applyDrMode(mode: DrMode) {
        if (mode == drMode) {
            return
        }
        Log.d(TAG, "applyDrMode: $drMode -> $mode")
        drMode = mode
        updateDrTicker()
    }

    private fun updateDrTicker() {
        if (!isRunning.get()) {
            return
        }
        when {
            drIntervalSec <= 0 -> {
                Log.d(TAG, "updateDrTicker: DR disabled (intervalSec=$drIntervalSec)")
                stopDrTicker()
            }
            drMode == DrMode.Prediction -> {
                startDrTicker()
            }
            drMode == DrMode.Completion -> {
                Log.d(TAG, "updateDrTicker: completion mode uses backfill only (no real-time ticker)")
                stopDrTicker()
            }
        }
    }

    private fun scheduleDrBackfill(
        startMillis: Long,
        endMillis: Long,
        endHoldLat: Double,
        endHoldLon: Double,
        endAccuracyM: Float?,
        endSpeedMps: Float
    ) {
        val engine = dr ?: return
        if (endMillis <= startMillis) {
            return
        }
        serviceScope.launch(Dispatchers.Default) {
            try {
                backfillDrSegment(
                    dr = engine,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    endHoldLat = endHoldLat,
                    endHoldLon = endHoldLon,
                    endAccuracyM = endAccuracyM,
                    endSpeedMps = endSpeedMps.toDouble()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "dr backfill failed", t)
            }
        }
    }

    private suspend fun backfillDrSegment(
        dr: DeadReckoning,
        startMillis: Long,
        endMillis: Long,
        endHoldLat: Double,
        endHoldLon: Double,
        endAccuracyM: Float?,
        endSpeedMps: Double
    ) {
        val pts: List<PredictedPoint> = try {
            dr.predict(fromMillis = startMillis, toMillis = endMillis)
        } catch (t: Throwable) {
            Log.w(TAG, "dr.predict(backfill) from=$startMillis to=$endMillis", t)
            emptyList()
        }
        if (pts.isEmpty()) {
            Log.d(TAG, "backfillDrSegment: no predicted points for segment [$startMillis, $endMillis]")
            return
        }

        val last = pts.last()
        val dtTotalMillis = (endMillis - startMillis).toDouble()
        if (dtTotalMillis <= 0.0) {
            return
        }

        val errorLat = endHoldLat - last.lat
        val errorLon = endHoldLon - last.lon

        val staticFlag = try {
            dr.isLikelyStatic()
        } catch (t: Throwable) {
            Log.w(TAG, "dr.isLikelyStatic() in backfill", t)
            false
        }
        DrDebugState.update(isStatic = staticFlag, lastUpdateMillis = endMillis)

        val bat = BatteryStatusReader.read(applicationContext)

        for (p in pts) {
            val t = p.timestampMillis
            if (t < startMillis || t > endMillis) {
                continue
            }
            val alphaRaw = (t - startMillis).toDouble() / dtTotalMillis
            val alpha: Double = when {
                alphaRaw < 0.0 -> 0.0
                alphaRaw > 1.0 -> 1.0
                else -> alphaRaw
            }
            val correctedLat = p.lat + alpha * errorLat
            val correctedLon = p.lon + alpha * errorLon

            val headingForDr = headingSensor.headingTrueDeg()?.toDouble() ?: 0.0
            val courseForDr = if (!lastCourseDeg.isNaN()) {
                lastCourseDeg
            } else {
                headingForDr
            }

            val sample = LocationSample(
                id = 0,
                timeMillis = t,
                lat = correctedLat,
                lon = correctedLon,
                accuracy = p.accuracyM ?: (endAccuracyM ?: 0f),
                provider = "dead_reckoning",
                headingDeg = headingForDr,
                courseDeg = courseForDr,
                speedMps = (p.speedMps?.toDouble() ?: endSpeedMps),
                gnssUsed = -1,
                gnssTotal = -1,
                cn0 = 0.0,
                batteryPercent = bat.percent,
                isCharging = bat.isCharging
            )

            val sigValue = sig(
                sample.lat, sample.lon, sample.accuracy,
                sample.provider,
                sample.headingDeg,
                sample.courseDeg ?: 0.0,
                sample.speedMps,
                sample.gnssUsed, sample.gnssTotal, sample.cn0,
                sample.batteryPercent, sample.isCharging
            )
            var skip = false
            synchronized(drInsertLock) {
                if (sigValue == lastDrInsertSig && t - lastDrInsertMillis <= (drIntervalSec * 500L)) {
                    skip = true
                } else {
                    lastDrInsertSig = sigValue
                    lastDrInsertMillis = t
                }
            }
            if (skip) {
                Log.d("DB/TRACE", "dr backfill insert skipped (dup guard)")
                continue
            }

            Log.d(
                "DB/TRACE",
                "before-insert provider=dead_reckoning(backfill) t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}"
            )
            try {
                StorageService.insertLocation(applicationContext, sample)
                Log.d(
                    "DB/TRACE",
                    "after-insert ok provider=dead_reckoning(backfill) t=${sample.timeMillis}"
                )
            } catch (tInsert: Throwable) {
                Log.e(
                    "DB/TRACE",
                    "insert failed provider=dead_reckoning(backfill) t=${sample.timeMillis}",
                    tInsert
                )
            }
        }
    }

    // ------------------------
    //   Notification
    // ------------------------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("geo", "Geo", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * Ensure foreground service notification is visible.
     * Safe to call on every GPS/DR tick; it just refreshes the notification.
     * Called regularly from DR ticker and GPS callbacks.
     */
    private fun ensureNotificationVisible() {
        if (!isRunning.get()) return
        serviceScope.launch(Dispatchers.Main.immediate) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (t: Throwable) {
                Log.w(TAG, "ensureNotificationVisible failed", t)
            }
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, "geo")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            val notification = builder
                .setContentTitle("GeoLocation")
                .setContentText("Running...")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()

            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

            notification
        } else {
            val builder = NotificationCompat.Builder(this, "geo")
                .setContentTitle("GeoLocation")
                .setContentText("Running...")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)

            val notification = builder.build()
            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

            notification
        }
    }

    // ------------------------
    //   Helper
    // ------------------------
    private fun sig(
        lat: Double, lon: Double, acc: Float,
        provider: String,
        heading: Double, course: Double, speed: Double,
        used: Int, total: Int, cn0: Double,
        bat: Int, chg: Boolean
    ): Long {
        var h = 1125899906842597L
        fun mix(x: Long) { h = 31L * h + x }
        fun d(v: Double) = java.lang.Double.doubleToRawLongBits(v)
        fun f(v: Float)  = java.lang.Float.floatToRawIntBits(v).toLong()
        mix(d(lat)); mix(d(lon)); mix(f(acc))
        mix(provider.hashCode().toLong())
        mix(d(heading)); mix(d(course)); mix(d(speed))
        mix(used.toLong()); mix(total.toLong()); mix(d(cn0))
        mix(bat.toLong()); mix(if (chg) 1L else 0L)
        return h
    }

    private fun adjustDrSample(lat: Double, lon: Double, speedMps: Double): Pair<Double, Double> {
        // When DeadReckoning reports "static", clamp the DR output to a
        // recent raw GPS position and treat DR as co-located with GPS.
        // To avoid snapping to obvious GPS outliers, walk the GPS history
        // backward and pick the last sample whose movement, approximate
        // acceleration, and accuracy are consistent with a static state.
        val debugSnapshot = DrDebugState.snapshot.value
        if (!debugSnapshot.isStatic) {
            return lat to lon
        }

        val history: List<GpsHistorySample> = synchronized(gpsHistoryLock) {
            gpsHistory.toList()
        }
        if (history.isEmpty()) {
            return lat to lon
        }

        val replacement = chooseStaticGpsPosition(history)
        return replacement ?: (lat to lon)
    }

    private fun chooseStaticGpsPosition(
        history: List<GpsHistorySample>
    ): Pair<Double, Double>? {
        if (history.isEmpty()) {
            return null
        }

        // Walk from the newest sample backward. The first sample that
        // passes static-mode checks is treated as the "true" GPS position.
        for (i in history.indices.reversed()) {
            val current = history[i]
            val previous = history.getOrNull(i - 1)
            if (isStaticGpsSampleReliable(current, previous)) {
                return current.lat to current.lon
            }
        }

        return null
    }

    private fun isStaticGpsSampleReliable(
        current: GpsHistorySample,
        previous: GpsHistorySample?
    ): Boolean {
        val acc = current.accuracyM
        if (acc.isNaN() || acc <= 0f) {
            return false
        }
        if (acc > GPS_STATIC_MAX_ACCURACY_M) {
            return false
        }

        if (previous != null) {
            val dtMillis = current.timeMillis - previous.timeMillis
            if (dtMillis > 0L) {
                val dtSec = dtMillis.toDouble() / 1000.0
                val stepMeters = distanceMeters(
                    previous.lat,
                    previous.lon,
                    current.lat,
                    current.lon
                )
                val stepSpeed = stepMeters / dtSec
                if (stepMeters > GPS_STATIC_MAX_STEP_METERS) {
                    return false
                }
                if (stepSpeed > GPS_STATIC_MAX_SPEED_MPS) {
                    return false
                }

                val dv = abs(current.speedMps - previous.speedMps)
                val accel = dv / dtSec
                if (accel > GPS_STATIC_MAX_ACCEL_MPS2) {
                    return false
                }
            }
        }

        return true
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val meanLatRad = Math.toRadians((lat1 + lat2) * 0.5)
        val dLatRad = Math.toRadians(lat2 - lat1)
        val dLonRad = Math.toRadians(lon2 - lon1)

        val north = dLatRad * 6371000.0
        val east = dLonRad * cos(meanLatRad) * 6371000.0

        return hypot(east, north)
    }
}
