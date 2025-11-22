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
import androidx.core.app.NotificationCompat
import com.mapconductor.plugin.provider.storageservice.StorageService
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
import kotlin.math.max
import kotlin.math.min

class GeoLocationService : Service() {

    companion object {
        private const val TAG = "GeoLocationService"
        private const val NOTIFICATION_ID = 1

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

    // Course(進行方向) 推定用の前回位置 & 直近 Course
    @Volatile private var lastCourseDeg: Double = Double.NaN
    @Volatile private var lastCourseLat: Double? = null
    @Volatile private var lastCourseLon: Double? = null

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
        // サンプリング間隔（GPS）は SettingsRepository の Flow から常に同期する
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
        // DR 予測間隔も Flow から同期し、Save&Apply 経由・手動起動どちらでも同じ値を使う
        serviceScope.launch {
            SettingsRepository.drIntervalSecFlow(applicationContext)
                .distinctUntilChanged()
                .collect { sec ->
                    Log.d(TAG, "dr interval changed: $drIntervalSec -> $sec")
                    applyDrInterval(sec)
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
        startForeground(NOTIFICATION_ID, buildNotification())
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
            Log.d(
                TAG,
                "onLocationResult: lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}, " +
                        "speed=${loc.speed}, bear=${loc.bearing}"
            )
            handleLocation(loc)
        }
    }

    private fun handleLocation(location: Location) {
        // Foreground 通知がユーザー操作で隠されていても、
        // 次の GPS 取得タイミングで再掲できるようにする。
        ensureNotificationVisible()

        val now = System.currentTimeMillis()
        val lastFix = lastFixMillis

        val headingDeg: Float = headingSensor.headingTrueDeg() ?: 0f
        val speedMps: Float = max(0f, location.speed.takeIf { !it.isNaN() } ?: 0f)

        // --- Course(進行方向) の推定 ---
        val prevLat = lastCourseLat
        val prevLon = lastCourseLon

        val rawBearing = location.bearing
        val hasBearing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.hasBearing()
        } else {
            !rawBearing.isNaN()
        }

        val courseDeg: Double = when {
            // 1) provider が bearing を出してくれるなら、それを優先して使う
            hasBearing && !rawBearing.isNaN() -> {
                val normalized = ((rawBearing % 360f) + 360f) % 360f
                lastCourseDeg = normalized.toDouble()
                normalized.toDouble()
            }

            // 2) bearing が取れない場合は、直前の位置との差分から進行方向を推定
            prevLat != null && prevLon != null -> {
                val prev = Location(location.provider ?: "gps").apply {
                    latitude = prevLat
                    longitude = prevLon
                }
                val distance = prev.distanceTo(location) // [m]
                if (distance >= 0.5f) {                  // ★ 閾値をかなり下げる（0.5m）
                    val b = prev.bearingTo(location)
                    if (!b.isNaN()) {
                        val normalized = ((b % 360f) + 360f) % 360f
                        lastCourseDeg = normalized.toDouble()
                    }
                }

                if (lastCourseDeg.isNaN()) {
                    Double.NaN   // まだ一度も決まっていない
                } else {
                    lastCourseDeg
                }
            }

            // 3) それでも決められない場合は、前回の値を維持
            !lastCourseDeg.isNaN() -> lastCourseDeg

            // 4) 何も情報がない最初の一発目など → NaN
            else -> Double.NaN
        }

        // 直近位置を更新（次回の course 計算用）
        lastCourseLat = location.latitude
        lastCourseLon = location.longitude

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
                courseDeg,                    // GPS はこの計算済み courseDeg を使う
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
            val stepMs = (drIntervalSec * 1000L).coerceAtLeast(1000L)
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
                                    Log.w(
                                        TAG,
                                        "dr.predict(backfill) from=$lastFix to=$t",
                                        e
                                    )
                                    emptyList()
                                }
                                val p = pts.lastOrNull() ?: continue
                                val bat = BatteryStatusReader.read(applicationContext)

                                // ★ DR 用の heading / course を決定
                                val headingForDr = headingSensor.headingTrueDeg()?.toDouble() ?: 0.0
                                val courseForDr = if (!lastCourseDeg.isNaN()) {
                                    lastCourseDeg
                                } else {
                                    headingForDr
                                }

                                val sample = LocationSample(
                                    id = 0,
                                    timeMillis = t,              // バックフィル対象の “過去時刻”
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
                                Log.d(
                                    "DB/TRACE",
                                    "before-insert provider=dead_reckoning(backfill) t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}"
                                )
                                StorageService.insertLocation(applicationContext, sample)
                                Log.d(
                                    "DB/TRACE",
                                    "after-insert ok provider=dead_reckoning(backfill) t=${sample.timeMillis}"
                                )
                            } catch (e: Throwable) {
                                Log.e(
                                    "DB/TRACE",
                                    "insert failed provider=dead_reckoning(backfill)",
                                    e
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------
    //   DR ticker（リアルタイム）
    // ------------------------
    private fun startDrTicker() {
        stopDrTicker()
        val d = dr ?: return
        Log.d(TAG, "startDrTicker interval=${drIntervalSec}s")
        drTickerJob = serviceScope.launch(Dispatchers.IO) {
            while (isRunning.get()) {
                // GPS 更新がしばらく無い場合でも、DR の周期で
                // Foreground 通知を再掲できるようにする。
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
                            val bat = BatteryStatusReader.read(applicationContext)

                            // ★ DR 用の heading / course を決定（リアルタイム版）
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
                            // 重複抑止（DR）
                            val sig = sig(
                                sample.lat, sample.lon, sample.accuracy,
                                sample.provider,
                                sample.headingDeg,
                                sample.courseDeg ?: 0.0,   // nullable ではないが 0.0 fallback 付き
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
                                Log.d(
                                    "DB/TRACE",
                                    "before-insert provider=dead_reckoning(ticker) t=${sample.timeMillis} lat=${sample.lat} lon=${sample.lon}"
                                )
                                try {
                                    StorageService.insertLocation(applicationContext, sample)
                                    Log.d(
                                        "DB/TRACE",
                                        "after-insert ok provider=dead_reckoning(ticker) t=${sample.timeMillis}"
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
        val clamped = max(1, sec)
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

    /**
     * Foreground Service 用の通知がユーザー操作で一時的に隠されても、
     * 次の GPS/DR のタイミングで再掲するためのヘルパ。
     * 毎回呼んでも startForeground による通知更新として扱われるだけなので、
     * DR ticker や GPS コールバックからの定期呼び出しを前提とする。
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
                .setContentText("Running…")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()

            notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

            notification
        } else {
            val builder = NotificationCompat.Builder(this, "geo")
                .setContentTitle("GeoLocation")
                .setContentText("Running…")
                .setSmallIcon(R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)

            val notification = builder.build()
            notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

            notification
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
