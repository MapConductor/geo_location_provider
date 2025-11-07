package com.mapconductor.plugin.provider.geolocation.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoning
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.GpsFix
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.PredictedPoint
import com.mapconductor.plugin.provider.geolocation.deadreckoning.impl.DeadReckoningImpl
import com.mapconductor.plugin.provider.geolocation.util.BatteryStatusReader
import com.mapconductor.plugin.provider.geolocation.util.GnssStatusSampler
import com.mapconductor.plugin.provider.geolocation.util.HeadingSensor
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import com.mapconductor.plugin.provider.storageservice.StorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class GeoLocationService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"

        /** GPS 取得間隔の更新 (ms) */
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS = "EXTRA_UPDATE_MS"

        /** 新：DR 予測間隔の更新 (sec) */
        const val ACTION_UPDATE_DR_INTERVAL = "ACTION_UPDATE_DR_INTERVAL"
        const val EXTRA_DR_INTERVAL_SEC = "EXTRA_DR_INTERVAL_SEC"
    }

    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var headingSensor: HeadingSensor
    private lateinit var gnssSampler: GnssStatusSampler
    private var dr: DeadReckoning? = null

    @Volatile private var updateIntervalMs: Long = 30_000L
    @Volatile private var isRunning = AtomicBoolean(false)
    @Volatile private var lastFixMillis: Long? = null // 直近の Fix 時刻（予測の基点に使用）

    // DR（リアルタイム）タイカー
    private var drTickerJob: Job? = null
    @Volatile private var drIntervalSec: Int = 5  // 新・UI連携がなくても動く既定値

    // 重複挿入抑制（GPS/DR）
    @Volatile private var lastInsertMillis: Long = 0L
    @Volatile private var lastInsertSig: Long = 0L
    private val insertLock = Any()
    @Volatile private var lastDrInsertMillis: Long = 0L
    @Volatile private var lastDrInsertSig: Long = 0L
    private val drInsertLock = Any()

    fun getUpdateIntervalMs(): Long = updateIntervalMs

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        headingSensor = HeadingSensor(applicationContext).also { it.start() }
        gnssSampler = GnssStatusSampler(applicationContext).also { it.start(mainLooper) }
        dr = DeadReckoningImpl(applicationContext).also { it.start() }
        ensureChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDrTicker()
        try { gnssSampler.stop() } catch (_: Throwable) {}
        try { headingSensor.stop() } catch (_: Throwable) {}
        try { dr?.stop() } catch (_: Throwable) {}
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocation()
            ACTION_STOP  -> stopLocation()

            ACTION_UPDATE_INTERVAL -> {
                val ms = intent.getLongExtra(EXTRA_UPDATE_MS, updateIntervalMs)
                updateIntervalMs = max(5000L, ms) // GPS側は最小5秒
                if (isRunning.get()) {
                    serviceScope.launch { restartLocationUpdates() }
                }
            }

            ACTION_UPDATE_DR_INTERVAL -> {
                val sec = intent.getIntExtra(EXTRA_DR_INTERVAL_SEC, drIntervalSec)
                applyDrInterval(sec)
            }
        }
        return START_STICKY
    }

    private fun startLocation() {
        if (isRunning.getAndSet(true)) return
        startForeground(1, buildNotification())
        serviceScope.launch { restartLocationUpdates() }
        startDrTicker() // DR リアルタイムを開始
    }

    private fun stopLocation() {
        if (!isRunning.getAndSet(false)) return
        stopForeground(STOP_FOREGROUND_DETACH)
        try { fusedClient.removeLocationUpdates(callback) } catch (_: Throwable) {}
        stopDrTicker()
    }

    // ------------------------
    //   GPS 更新制御
    // ------------------------
    private suspend fun restartLocationUpdates() {
        try { fusedClient.removeLocationUpdates(callback) } catch (_: Throwable) {}
        val req = LocationRequest.Builder(updateIntervalMs)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(updateIntervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        fusedClient.requestLocationUpdates(req, callback, mainLooper)
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            handleLocation(loc)
        }
    }

    private fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        val lastFix = lastFixMillis

        val headingDeg: Float = headingSensor.headingTrueDeg() ?: 0f
        val courseDeg: Float = location.bearing.takeIf { !it.isNaN() } ?: 0f
        val speedMps: Float = max(0f, location.speed.takeIf { !it.isNaN() } ?: 0f)

        val gnss = try { gnssSampler.snapshot() } catch (_: Throwable) { null }
        val used: Int? = gnss?.used
        val total: Int? = gnss?.total
        val cn0: Double? = gnss?.cn0Mean?.toDouble()

        // 1) GPS を保存
        run {
            val bat = BatteryStatusReader.read(applicationContext)
            val sample = LocationSample(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = location.accuracy,
                provider = "gps",
                headingDeg = headingDeg.toDouble(),
                courseDeg = courseDeg.toDouble(),
                speedMps = speedMps.toDouble(),
                gnssUsed = used ?: -1,
                gnssTotal = total ?: -1,
                cn0 = cn0 ?: Double.NaN,
                batteryPercent = bat.percent,
                isCharging = bat.isCharging
            )
            // 重複抑制（簡易）
            val sig = sig(
                sample.lat, sample.lon, sample.accuracy,
                sample.provider, sample.headingDeg, sample.courseDeg, sample.speedMps,
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
                    try { StorageService.insertLocation(applicationContext, sample) } catch (_: Throwable) {}
                }
            }
        }

        // 2) DRへ Fix を通知（基準更新のみ。タイマ同期はしない）
        lastFixMillis = now
        serviceScope.launch(Dispatchers.Default) {
            try {
                dr?.submitGpsFix(
                    GpsFix(
                        timestampMillis = now,
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = location.accuracy,
                        speedMps = speedMps.takeIf { it > 0f }
                    )
                )
            } catch (_: Throwable) { /* no-op */ }
        }

        // 3) バックフィル：直前Fix→今回Fixの間を DR間隔(秒)で分割し保存
        if (lastFix != null) {
            val stepMs = (drIntervalSec * 1000L).coerceAtLeast(5000L) // 安全のため最小5秒
            val targets = generateSequence(lastFix + stepMs) { it + stepMs }
                .takeWhile { it < now - 100L }
                .toList()
            if (targets.isNotEmpty()) {
                val d = dr
                if (d != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        for (t in targets) {
                            try {
                                val pts: List<PredictedPoint> = d.predict(fromMillis = lastFix, toMillis = t)
                                val p = pts.lastOrNull() ?: continue
                                val bat = BatteryStatusReader.read(applicationContext)
                                val sample = LocationSample(
                                    lat = p.lat,
                                    lon = p.lon,
                                    accuracy = (p.accuracyM ?: Float.NaN),
                                    provider = "dead_reckoning",
                                    headingDeg = Double.NaN,
                                    courseDeg = Double.NaN,
                                    speedMps = (p.speedMps ?: Float.NaN).toDouble(),
                                    gnssUsed = -1,
                                    gnssTotal = -1,
                                    cn0 = Double.NaN,
                                    batteryPercent = bat.percent,
                                    isCharging = bat.isCharging
                                )
                                StorageService.insertLocation(applicationContext, sample)
                            } catch (_: Throwable) { /* continue */ }
                        }
                    }
                }
            }
        }
    }

    // ------------------------
    //   DR タイカー（リアルタイム）
    // ------------------------
    private fun startDrTicker() {
        stopDrTicker()
        val d = dr ?: return
        drTickerJob = serviceScope.launch(Dispatchers.IO) {
            while (isRunning.get()) {
                val base = lastFixMillis
                try {
                    if (base != null) {
                        val now = System.currentTimeMillis()
                        val pts: List<PredictedPoint> = try {
                            d.predict(fromMillis = base, toMillis = now)
                        } catch (_: Throwable) { emptyList() }
                        val p = pts.lastOrNull()
                        if (p != null) {
                            val bat = BatteryStatusReader.read(applicationContext)
                            val sample = LocationSample(
                                lat = p.lat,
                                lon = p.lon,
                                accuracy = (p.accuracyM ?: Float.NaN),
                                provider = "dead_reckoning",
                                headingDeg = Double.NaN,
                                courseDeg = Double.NaN,
                                speedMps = (p.speedMps ?: Float.NaN).toDouble(),
                                gnssUsed = -1,
                                gnssTotal = -1,
                                cn0 = Double.NaN,
                                batteryPercent = bat.percent,
                                isCharging = bat.isCharging
                            )
                            // 重複抑止（DR）
                            val sig = sig(
                                sample.lat, sample.lon, sample.accuracy,
                                sample.provider, sample.headingDeg, sample.courseDeg, sample.speedMps,
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
                                try { StorageService.insertLocation(applicationContext, sample) } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (_: Throwable) { /* loop continue */ }
                delay((drIntervalSec * 1000L).coerceAtLeast(5000L))
            }
        }
    }

    private fun stopDrTicker() {
        drTickerJob?.cancel()
        drTickerJob = null
    }

    private fun applyDrInterval(sec: Int) {
        val clamped = max(5, sec)
        drIntervalSec = clamped
        if (isRunning.get()) {
            // すぐ反映（タイマ同期はしないが、周期は新値へ）
            startDrTicker()
        }
    }

    // ------------------------
    //   通知
    // ------------------------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("geo", "Geo", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "geo")
                .setContentTitle("GeoLocation")
                .setContentText("Running…")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("GeoLocation")
                .setContentText("Running…")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .build()
        }
    }

    // ------------------------
    //   補助
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
}
