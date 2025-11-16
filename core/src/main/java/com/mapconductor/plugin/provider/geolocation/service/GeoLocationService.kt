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
import android.util.Log
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
import com.mapconductor.plugin.provider.storageservice.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class GeoLocationService : Service() {

    companion object {
        private const val TAG = "GeoLocationService"

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
    @Volatile private var drIntervalSec: Int = 5  // 既定値

    // 重複挿入抑制（GPS/DR）
    @Volatile private var lastInsertMillis: Long = 0L
    @Volatile private var lastInsertSig: Long = 0L
    private val insertLock = Any()
    @Volatile private var lastDrInsertMillis: Long = 0L
    @Volatile private var lastDrInsertSig: Long = 0L
    private val drInsertLock = Any()

    fun getUpdateIntervalMs(): Long = updateIntervalMs

    fun isLocationRunning(): Boolean = isRunning.get()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        headingSensor = HeadingSensor(applicationContext).also { it.start() }
        gnssSampler = GnssStatusSampler(applicationContext).also { it.start(mainLooper) }
        dr = DeadReckoningImpl(applicationContext).also { it.start() }
        ensureChannel()

        // ---- 起動直後の乖離対策（storageserviceの設定を使用） ----
        // デフォルト値(30秒)で起動し、設定値は非同期で読み込む
        serviceScope.launch {
            SettingsRepository.intervalSecFlow(applicationContext)
                .distinctUntilChanged()
                .collect { sec ->
                    val ms = max(5_000L, sec * 1_000L)
                    if (ms != updateIntervalMs) {
                        Log.d(TAG, "interval changed: ${updateIntervalMs}ms -> ${ms}ms")
                        updateIntervalMs = ms
                        if (isRunning.get()) {
                            restartLocationUpdates()
                        }
                    }
                }
        }
        // ---- ここまで ----
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        stopDrTicker()
        try { gnssSampler.stop() } catch (t: Throwable) { Log.w(TAG, "gnssSampler.stop()", t) }
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
                updateIntervalMs = max(5000L, ms) // GPS側は最小5秒
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
        startForeground(1, buildNotification())
        serviceScope.launch { restartLocationUpdates() }
        startDrTicker() // DR リアルタイムを開始
    }

    private fun stopLocation() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "stopLocation")

        // FGS を外す
        stopForeground(STOP_FOREGROUND_DETACH)

        // 位置更新を停止
        try {
            fusedClient.removeLocationUpdates(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "removeLocationUpdates", t)
        }

        // DR のタイカー停止
        stopDrTicker()

        // ★重要★
        // Start/Stop ボタン側は「サービスに bind できるかどうか」で
        // 動作中判定をしているため、ここでサービス自体を終了しておく。
        stopSelf()
    }

    // ------------------------
    //   GPS 更新制御
    // ------------------------
    private suspend fun restartLocationUpdates() {
        Log.d(TAG, "restartLocationUpdates: interval=${updateIntervalMs}ms")
        try { fusedClient.removeLocationUpdates(callback) } catch (t: Throwable) {
            Log.w(TAG, "removeLocationUpdates (restart)", t)
        }
        val req = LocationRequest.Builder(updateIntervalMs)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(updateIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, callback, mainLooper)
            Log.d(TAG, "requestLocationUpdates() issued")
        } catch (t: Throwable) {
            Log.e(TAG, "requestLocationUpdates() failed", t)
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            Log.d(TAG, "onLocationResult: lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}, speed=${loc.speed}, bear=${loc.bearing}")
            handleLocation(loc)
        }
    }

    private fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        val lastFix = lastFixMillis

        val headingDeg: Float = headingSensor.headingTrueDeg() ?: 0f
        val courseDeg: Float = location.bearing.takeIf { !it.isNaN() } ?: 0f
        val speedMps: Float = max(0f, location.speed.takeIf { !it.isNaN() } ?: 0f)

        val gnss = try { gnssSampler.snapshot() } catch (t: Throwable) {
            Log.w(TAG, "gnssSampler.snapshot()", t); null
        }
        val used: Int? = gnss?.used
        val total: Int? = gnss?.total
        val cn0: Double? = gnss?.cn0Mean?.toDouble()

        // 1) GPS を保存
        run {
            val bat = BatteryStatusReader.read(applicationContext)
            val sample = LocationSample(
                id = 0,
                timeMillis = now,
                lat = location.latitude,
                lon = location.longitude,
                accuracy = location.accuracy,
                provider = "gps",
                headingDeg = headingDeg.toDouble(),
                courseDeg = courseDeg.toDouble(),
                speedMps = speedMps.toDouble(),
                gnssUsed = used ?: -1,
                gnssTotal = total ?: -1,
                cn0 = cn0 ?: 0.0,
                batteryPercent = bat.percent,
                isCharging = bat.isCharging
            )
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
                    Log.d("DB/TRACE", "before-insert provider=gps t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}")
                    try {
                        StorageService.insertLocation(applicationContext, sample)
                        Log.d("DB/TRACE", "after-insert ok provider=gps t=${sample.timeMillis}")
                    } catch (t: Throwable) {
                        Log.e("DB/TRACE", "insert failed provider=gps t=${sample.timeMillis}", t)
                    }
                }
            } else {
                Log.d("DB/TRACE", "gps insert skipped (dup guard)")
            }
        }

        // 2) DRへ Fix を通知（基準更新のみ）
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
            } catch (t: Throwable) {
                Log.w(TAG, "dr.submitGpsFix()", t)
            }
        }

        // 3) バックフィル：直前Fix→今回Fixの間を DR間隔(秒)で分割し保存
        if (lastFix != null) {
            val stepMs = (drIntervalSec * 1000L).coerceAtLeast(5000L)
            val targets = generateSequence(lastFix + stepMs) { it + stepMs }
                .takeWhile { it < now - 100L }
                .toList()
            if (targets.isNotEmpty()) {
                val d = dr
                if (d != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        for (t in targets) {
                            try {
                                val pts: List<PredictedPoint> = try {
                                    d.predict(fromMillis = lastFix, toMillis = t)
                                } catch (e: Throwable) {
                                    Log.w(TAG, "dr.predict(backfill) from=$lastFix to=$t", e)
                                    emptyList()
                                }
                                val p = pts.lastOrNull() ?: continue
                                val bat = BatteryStatusReader.read(applicationContext)
                                val sample = LocationSample(
                                    id = 0,
                                    timeMillis = t,              // バックフィル対象の “過去時刻”
                                    lat = p.lat,
                                    lon = p.lon,
                                    accuracy = (p.accuracyM ?: 0f),
                                    provider = "dead_reckoning",
                                    headingDeg = 0.0,            // ★ NOT NULL 対策
                                    courseDeg = 0.0,             // ★ NOT NULL 対策
                                    speedMps = (p.speedMps ?: Float.NaN).toDouble(),
                                    gnssUsed = -1,
                                    gnssTotal = -1,
                                    cn0 = 0.0,
                                    batteryPercent = bat.percent,
                                    isCharging = bat.isCharging
                                )
                                Log.d("DB/TRACE", "before-insert provider=dead_reckoning(backfill) t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}")
                                StorageService.insertLocation(applicationContext, sample)
                                Log.d("DB/TRACE", "after-insert ok provider=dead_reckoning(backfill) t=${sample.timeMillis}")
                            } catch (e: Throwable) {
                                Log.e("DB/TRACE", "insert failed provider=dead_reckoning(backfill)", e)
                            }
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
        Log.d(TAG, "startDrTicker interval=${drIntervalSec}s")
        drTickerJob = serviceScope.launch(Dispatchers.IO) {
            while (isRunning.get()) {
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
                            val bat = BatteryStatusReader.read(applicationContext)
                            val sample = LocationSample(
                                id = 0,
                                timeMillis = now,
                                lat = p.lat,
                                lon = p.lon,
                                accuracy = (p.accuracyM ?: 0f),
                                provider = "dead_reckoning",
                                headingDeg = 0.0,        // ★ NOT NULL 対策
                                courseDeg = 0.0,         // ★ NOT NULL 対策
                                speedMps = (p.speedMps ?: Float.NaN).toDouble(),
                                gnssUsed = -1,
                                gnssTotal = -1,
                                cn0 = 0.0,
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
                                Log.d("DB/TRACE", "before-insert provider=dead_reckoning(ticker) t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}")
                                try {
                                    StorageService.insertLocation(applicationContext, sample)
                                    Log.d("DB/TRACE", "after-insert ok provider=dead_reckoning(ticker) t=${sample.timeMillis}")
                                } catch (e: Throwable) {
                                    Log.e("DB/TRACE", "insert failed provider=dead_reckoning(ticker)", e)
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
                delay((drIntervalSec * 1000L).coerceAtLeast(5000L))
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
        val clamped = max(5, sec)
        Log.d(TAG, "applyDrInterval: $drIntervalSec -> $clamped")
        drIntervalSec = clamped
        if (isRunning.get()) {
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
