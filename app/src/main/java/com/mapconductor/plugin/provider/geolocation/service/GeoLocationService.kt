package com.mapconductor.plugin.provider.geolocation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import com.mapconductor.plugin.provider.geolocation.util.GnssStatusSampler
import com.mapconductor.plugin.provider.geolocation.util.HeadingSensor
import com.mapconductor.plugin.provider.geolocation.util.BatteryStatusReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class GeoLocationService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"

        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS = "EXTRA_UPDATE_MS"

        private const val CHANNEL_ID = "location_channel"
        private const val NOTIF_ID = 1
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- 状態 ---
    /** 設定上の取得間隔（通知表示・再購読に使用） */
    private var updateIntervalMs: Long = 5_000L
    /** 直近の測位時刻（通知で「前回取得時間」に使用） */
    private var lastFixMillis: Long? = null

    /** FusedLocation */
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCb: com.google.android.gms.location.LocationCallback? = null
    /** “購読中” フラグ（二重購読の根絶） */
    private val subscribed = AtomicBoolean(false)

    // ヘディング／GNSS
    private lateinit var headingSensor: HeadingSensor
    private lateinit var gnssSampler: GnssStatusSampler

    // 重複保存ガード
    @Volatile private var lastInsertMillis: Long = 0L
    @Volatile private var lastInsertSig: Long = 0L
    private val insertLock = Any()

    fun getUpdateIntervalMs(): Long = updateIntervalMs

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedClient = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(this)

        headingSensor = HeadingSensor(applicationContext).also { it.start() }
        gnssSampler = GnssStatusSampler(applicationContext).also { it.start(mainLooper) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                startForeground(NOTIF_ID, buildStatusNotification(lastMillis = null))
                serviceScope.launch(Dispatchers.IO) {
                    restartLocationUpdates()
                }
            }
            ACTION_UPDATE_INTERVAL -> {
                val newMs = intent.getLongExtra(EXTRA_UPDATE_MS, -1L)
                if (newMs > 0L) {
                    updateIntervalMs = newMs
                    serviceScope.launch(Dispatchers.IO) {
                        restartLocationUpdates()
                        // 設定値が変わったので通知も即時更新
                        updateStatusNotification(lastFixMillis)
                    }
                }
            }
            ACTION_STOP -> {
                stopLocationUpdatesSafely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (::headingSensor.isInitialized) headingSensor.stop()
            if (::gnssSampler.isInitialized) gnssSampler.stop()
        } catch (_: Throwable) { /* no-op */ }

        stopLocationUpdatesSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ========= 通知 =========

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Location", NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildStatusNotification(lastMillis: Long?): Notification {
        val lastText = "前回取得時刻 : " + Formatters.timeJst(lastMillis)
        // 表示は “実行中の設定値” を使用（②の乖離を解消）
        val intervalText = "取得間隔     : " + formatInterval(updateIntervalMs)

        val smallIconRes = android.R.drawable.ic_menu_mylocation
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIconRes)
                .setContentTitle("位置取得を実行中")
                .setContentText("$lastText  |  $intervalText")
                .setStyle(Notification.BigTextStyle().bigText("$lastText\n$intervalText"))
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
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildStatusNotification(lastMillis))
    }

    private fun formatInterval(ms: Long): String {
        val s = (ms / 1000.0)
        return if (s < 60) String.format("%.1fs", s) else String.format("%.0fs", s)
    }

    // ========= 購読管理 =========

    /** 現在の購読を停止（例外は握りつぶし） */
    private fun stopLocationUpdatesSafely() {
        try {
            locationCb?.let { cb ->
                fusedClient.removeLocationUpdates(cb)
            }
        } catch (_: Throwable) { /* no-op */ }
        locationCb = null
        subscribed.set(false)
    }

    /** 設定値で購読を開始（① 反映・③ 重複防止） */
    private suspend fun startLocationUpdatesSafely() {
        if (subscribed.get()) return  // 二重購読を回避
        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            updateIntervalMs
        ).build()

        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                val loc = r.lastLocation ?: return
                // ヘディングの偏差を更新（真北化用）— 高度と時刻を含めて渡す
                try {
                    val alt: Float = if (loc.hasAltitude()) loc.altitude.toFloat() else 0f
                    headingSensor.updateDeclination(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitudeMeters = alt,
                        timeMillis = System.currentTimeMillis()
                    )
                } catch (_: Throwable) { /* best-effort */ }

                serviceScope.launch(Dispatchers.IO) {
                    handleFix(loc)
                }
            }
        }
        locationCb = cb
        fusedClient.requestLocationUpdates(req, cb, mainLooper)
        subscribed.set(true)
    }

    /** 強制的に “止めて → 始める” */
    private suspend fun restartLocationUpdates() {
        stopLocationUpdatesSafely()
        startLocationUpdatesSafely()
    }

    // ========= コールバック：保存（③ 同値の重複保存禁止） =========

    private suspend fun handleFix(location: Location) {
        val now = System.currentTimeMillis()
        lastFixMillis = now

        // ヘディング/進行方向/速度
        val headingDeg: Float = try {
            // nullable を非null化
            headingSensor.headingTrueDeg() ?: 0f
        } catch (_: Throwable) { 0f }
        val courseDeg: Float = location.bearing.takeIf { !it.isNaN() } ?: 0f
        val speedMps: Float = max(0f, location.speed.takeIf { !it.isNaN() } ?: 0f)

        // GNSS
        val gnss = try { gnssSampler.snapshot() } catch (_: Throwable) { null }
        val used: Int? = gnss?.used
        val total: Int? = gnss?.total
        val cn0: Float? = gnss?.cn0Mean?.toFloat()

        // バッテリー
        val bat = BatteryStatusReader.read(applicationContext)

        // 重複保存ガード：
        //   同一（lat,lon,accuracy,provider,heading,course,speed,used,total,cn0,battery）かつ 400ms 以内ならスキップ
        val sig = sig(
            location.latitude, location.longitude, location.accuracy,
            location.provider ?: "fused",
            headingDeg.toDouble(), courseDeg.toDouble(), speedMps.toDouble(),
            used ?: -1, total ?: -1, (cn0 ?: Float.NaN).toDouble(),
            bat.percent, bat.isCharging
        )
        synchronized(insertLock) {
            if (sig == lastInsertSig && now - lastInsertMillis <= 400) {
                updateStatusNotification(lastFixMillis)
                return
            }
            lastInsertSig = sig
            lastInsertMillis = now
        }

        // DB へ保存
        val providerName = location.provider ?: "fused"
        val dao = AppDatabase.get(applicationContext).locationSampleDao()
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

        // 通知更新（②：実行中の updateIntervalMs を表示）
        updateStatusNotification(lastFixMillis)
    }

    private fun sig(
        lat: Double, lon: Double, acc: Float, provider: String,
        heading: Double, course: Double, speed: Double,
        used: Int, total: Int, cn0: Double,
        batPct: Int, charging: Boolean
    ): Long {
        // 小数は丸めて同一判定をやや緩くする
        fun q(x: Double, scale: Double) = (x * scale).toLong()
        var h = 1469598103934665603L // FNV-1a 64-bit
        fun mix(v: Long) {
            h = h xor v
            h *= 1099511628211L
        }
        mix(q(lat, 1e6))
        mix(q(lon, 1e6))
        mix(acc.toLong())
        mix(provider.hashCode().toLong())
        mix(q(heading, 10.0))
        mix(q(course, 10.0))
        mix(q(speed, 100.0))
        mix(used.toLong()); mix(total.toLong())
        mix(q(cn0, 10.0))
        mix(batPct.toLong()); mix(if (charging) 1 else 0)
        return h
    }
}
