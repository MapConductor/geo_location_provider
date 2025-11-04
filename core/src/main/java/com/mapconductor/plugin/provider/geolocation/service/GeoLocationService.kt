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
import com.mapconductor.plugin.provider.geolocation.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.util.BatteryStatusReader
import com.mapconductor.plugin.provider.geolocation.util.GnssStatusSampler
import com.mapconductor.plugin.provider.geolocation.util.HeadingSensor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GeoLocationService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS = "EXTRA_UPDATE_MS"
        const val ACTION_UPDATE_PREDICT = "ACTION_UPDATE_PREDICT"
        const val EXTRA_PREDICT_COUNT = "EXTRA_PREDICT_COUNT"

        private const val NOTIF_CHANNEL_ID = "geo_location_status"
        private const val NOTIF_ID = 1001
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
    private var predictCount: Int = 5 // 予測回数（UI 連携がなくても動く既定値）

    @Volatile private var updateIntervalMs: Long = 30_000L
    @Volatile private var isRunning = AtomicBoolean(false)
    @Volatile private var lastFixMillis: Long? = null // 直近の Fix 時刻（予測の基点に使用）

    @Volatile private var lastInsertMillis: Long = 0L
    @Volatile private var lastInsertSig: Long = 0L
    private val insertLock = Any()

    // ★ DRリアルタイム発行用
    private var drTickerJob: Job? = null
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                startForeground(NOTIF_ID, buildStatusNotification(lastMillis = null))
                serviceScope.launch(Dispatchers.IO) { restartLocationUpdates() }
                restartDrTicker() // ★ 起動時からリアルタイムDRを回す
            }
            ACTION_UPDATE_INTERVAL -> {
                val newMs = intent.getLongExtra(EXTRA_UPDATE_MS, -1L)
                if (newMs > 0L) {
                    updateIntervalMs = newMs
                    serviceScope.launch(Dispatchers.IO) { restartLocationUpdates() }
                    restartDrTicker() // ★ 周期が変わるので再起動
                }
            }
            ACTION_UPDATE_PREDICT -> {
                val newCount = intent.getIntExtra(EXTRA_PREDICT_COUNT, -1)
                if (newCount >= 0) {
                    predictCount = newCount
                    restartDrTicker() // ★ 周期が変わるので再起動
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            fusedClient.removeLocationUpdates(callback)
        } catch (_: Throwable) {}
        try { headingSensor.stop() } catch (_: Throwable) {}
        try { gnssSampler.stop() } catch (_: Throwable) {}
        try { dr?.close() } catch (_: Throwable) {}
        dr = null

        drTickerJob?.cancel() // ★ 追加
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------
    //   Location 更新制御
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
        val cn0: Float? = gnss?.cn0Mean?.toFloat()

        val bat = BatteryStatusReader.read(applicationContext)

        val sig = sig(
            location.latitude, location.longitude, location.accuracy,
            location.provider ?: "fused",
            headingDeg.toDouble(), courseDeg.toDouble(), speedMps.toDouble(),
            used ?: -1, total ?: -1, (cn0 ?: Float.NaN).toDouble(),
            bat.percent, bat.isCharging
        )
        synchronized(insertLock) {
            if (sig == lastInsertSig && now - lastInsertMillis <= 400) {
                updateStatusNotification(lastFix)
                return
            }
            lastInsertSig = sig
            lastInsertMillis = now
        }

        // 1) GPS/Fused の真値を保存
        val providerName = location.provider ?: "fused"
        val dao = AppDatabase.get(applicationContext).locationSampleDao()
        serviceScope.launch(Dispatchers.IO) {
            dao.insert(
                LocationSample(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracy = location.accuracy,
                    provider = providerName,
                    batteryPct = bat.percent,
                    isCharging = bat.isCharging,
                    headingDeg = headingDeg,
                    courseDeg = courseDeg,
                    speedMps = speedMps,
                    gnssUsed = used,
                    gnssTotal = total,
                    gnssCn0Mean = cn0,
                    createdAt = now
                )
            )
        }

        // 2) DR に GPS Fix を供給
        dr?.let { d ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    d.submitGpsFix(
                        GpsFix(
                            timestampMillis = now,
                            lat = location.latitude,
                            lon = location.longitude,
                            accuracyM = location.accuracy,                 // Float をそのまま
                            speedMps = speedMps.takeIf { it > 0f }         // Float? で渡す
                        )
                    )
                } catch (_: Throwable) {
                    // no-op
                }
            }
        }

        // 3) 直前の Fix から今回 Fix までの間を predictCount 分割で予測し保存（バックフィル）
        if (lastFix != null && predictCount > 0) {
            val stepMs = (updateIntervalMs.toDouble() / (predictCount + 1)).toLong().coerceAtLeast(500L)
            val targets = (1..predictCount).map { t -> lastFix + stepMs * t }.filter { it < now - 100L }
            if (targets.isNotEmpty()) {
                val d = dr
                if (d != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        for (t in targets) {
                            try {
                                val pts: List<PredictedPoint> = d.predict(fromMillis = lastFix, toMillis = t)
                                val p = pts.lastOrNull() ?: continue
                                dao.insert(
                                    LocationSample(
                                        lat = p.lat,
                                        lon = p.lon,
                                        accuracy = (p.accuracyM?.toFloat() ?: Float.NaN), // ★ 明示キャスト
                                        provider = "dead_reckoning",
                                        batteryPct = bat.percent,
                                        isCharging = bat.isCharging,
                                        headingDeg = null,
                                        courseDeg = null,
                                        speedMps = p.speedMps?.toFloat(),                 // ★ 念のため Float? に
                                        gnssUsed = null,
                                        gnssTotal = null,
                                        gnssCn0Mean = null,
                                        createdAt = p.timestampMillis
                                    )
                                )
                            } catch (_: Throwable) {
                                // 予測失敗は無視（次回 Fix で補正）
                            }
                        }
                    }
                }
            }
        }

        lastFixMillis = now
        updateStatusNotification(lastFix)
    }

    // ------------------------
    //   DR リアルタイム発行ループ（GPS無しでも更新）
    // ------------------------
    private fun restartDrTicker() {
        drTickerJob?.cancel()
        val stepMs = ((updateIntervalMs / (predictCount + 1).coerceAtLeast(1))).coerceAtLeast(500L)
        drTickerJob = serviceScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    val base = lastFixMillis
                    val engine = dr
                    if (base != null && engine != null) {
                        val now = System.currentTimeMillis()
                        // 直近Fixから“今”までの推定を取得し、末尾（最新）だけを採用
                        val pts: List<PredictedPoint> = try {
                            engine.predict(fromMillis = base, toMillis = now)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        val p = pts.lastOrNull()
                        if (p != null) {
                            // ★ 型を明確化して Elvis による型崩れを防止
                            val accF: Float = p.accuracyM?.toFloat() ?: Float.NaN
                            val speedD: Double = p.speedMps?.toDouble() ?: Double.NaN

                            // 重複/過剰挿入の抑制
                            val bat = BatteryStatusReader.read(applicationContext)
                            val sig = sig(
                                p.lat, p.lon, accF,                       // ★ Float で渡す
                                "dead_reckoning",
                                Double.NaN, Double.NaN, speedD,           // ★ Double で渡す
                                -1, -1, Double.NaN,
                                bat.percent, bat.isCharging
                            )
                            var skip = false
                            synchronized(drInsertLock) {
                                if (sig == lastDrInsertSig && now - lastDrInsertMillis <= (stepMs / 2)) {
                                    skip = true
                                } else {
                                    lastDrInsertSig = sig
                                    lastDrInsertMillis = now
                                }
                            }
                            if (!skip) {
                                // DB に「現在の推定点」を即時反映（UIはRoom観測でリアルタイム更新）
                                val dao = AppDatabase.get(applicationContext).locationSampleDao()
                                launch(Dispatchers.IO) {
                                    try {
                                        dao.insert(
                                            LocationSample(
                                                lat = p.lat,
                                                lon = p.lon,
                                                accuracy = accF,                      // ★ Float で保存
                                                provider = "dead_reckoning",
                                                batteryPct = bat.percent,
                                                isCharging = bat.isCharging,
                                                headingDeg = null,
                                                courseDeg = null,
                                                speedMps = p.speedMps?.toFloat(),    // ★ Float? で保存
                                                gnssUsed = null,
                                                gnssTotal = null,
                                                gnssCn0Mean = null,
                                                createdAt = now // “いま”の時刻でリアルタイム表示
                                            )
                                        )
                                    } catch (_: Throwable) {
                                        // ignore
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 例外はループ継続
                }
                delay(stepMs)
            }
        }
    }

    // ------------------------
    //   通知
    // ------------------------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "GeoLocation Status",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildStatusNotification(lastMillis: Long?): Notification {
        val smallIconRes = R.drawable.ic_menu_mylocation
        val intervalText = "Interval: ${formatInterval(updateIntervalMs)}"
        val lastText = "Last: ${formatTimeJst(lastMillis)}"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(smallIconRes)
                .setContentTitle("位置取得を実行中")
                .setContentText("$lastText  |  $intervalText")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(smallIconRes)
                .setContentTitle("位置取得を実行中")
                .setContentText("$lastText  |  $intervalText")
                .setOngoing(true)
                .build()
        }
    }

    private fun updateStatusNotification(lastMillis: Long?) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildStatusNotification(lastMillis))
    }

    private fun formatInterval(ms: Long): String {
        val s = (ms / 1000.0)
        return if (s < 60) String.format(Locale.US, "%.1fs", s)
        else String.format(Locale.US, "%.0fs", s)
    }

    private fun formatTimeJst(millis: Long?): String {
        if (millis == null) return "-"
        val df = SimpleDateFormat("yyyy/MM/dd(EEE) HH:mm:ss", Locale.JAPAN)
        df.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return df.format(Date(millis))
    }

    // insertion signature hash（重複抑止の簡易仕組み）
    private fun sig(
        lat: Double, lon: Double, acc: Float, provider: String,
        heading: Double, course: Double, speed: Double,
        used: Int, total: Int, cn0: Double,
        batPct: Int, charging: Boolean
    ): Long {
        fun q(x: Double, scale: Double) = (x * scale).toLong()
        var h = 1469598103934665603L
        fun mix(v: Long) { h = h xor v; h *= 1099511628211L }
        mix(q(lat, 1e6)); mix(q(lon, 1e6)); mix(acc.toLong())
        mix(provider.hashCode().toLong())
        mix(q(heading, 10.0)); mix(q(course, 10.0)); mix(q(speed, 100.0))
        mix(used.toLong()); mix(total.toLong()); mix(q(cn0, 10.0))
        mix(batPct.toLong()); mix(if (charging) 1 else 0)
        return h
    }
}
