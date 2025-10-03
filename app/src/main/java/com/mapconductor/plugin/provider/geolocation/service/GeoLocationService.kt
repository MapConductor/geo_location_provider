package com.mapconductor.plugin.provider.geolocation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GeoLocationService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"

        // 設定変更（インターバル）を受け取る
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS        = "EXTRA_UPDATE_MS"

        private const val CHANNEL_ID = "geo_location"
        private const val NOTIF_ID   = 1001

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }
    private val binder = LocalBinder()

    /** 設定された取得間隔（ms）。既定値=5秒 */
    @Volatile
    private var updateIntervalMs: Long = 5_000L

    /** 直近の測位時刻（通知で「前回取得時間」を出すために保持） */
    private var lastFixMillis: Long? = null

    fun getUpdateIntervalMs(): Long = updateIntervalMs

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                // 起動直後は前回取得時間がまだないので lastMillis=null で常駐開始
                startForeground(NOTIF_ID, buildStatusNotification(lastMillis = null))
                _running.value = true

                serviceScope.launch(Dispatchers.IO) { startLocationUpdatesSafely() }
            }

            ACTION_UPDATE_INTERVAL -> {
                val newMs = intent.getLongExtra(EXTRA_UPDATE_MS, -1L)
                if (newMs > 0L) {
                    updateIntervalMs = newMs
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            stopLocationUpdatesSafely()
                        } finally {
                            startLocationUpdatesSafely()
                            // 設定値が変わったので通知の「取得間隔」も即時更新
                            updateStatusNotification(lastFixMillis)
                        }
                    }
                }
            }

            ACTION_STOP -> {
                stopLocationUpdatesSafely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                _running.value = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdatesSafely()
        serviceScope.cancel()
        _running.value = false
        super.onDestroy()
    }

    /** Android O+ ではチャンネル作成が必要 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "GeoLocation Running",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Location sampling in progress"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    /** 通知を現在の状態で更新（スワイプで消えない） */
    private fun updateStatusNotification(lastMillis: Long?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildStatusNotification(lastMillis))
    }

    /** 通知本文：「前回取得時間」「取得間隔(設定値)」 の2行 */
    private fun buildStatusNotification(lastMillis: Long?): Notification {
        val jst = ZoneId.of("Asia/Tokyo")
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val lastText = lastMillis?.let {
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), jst)
            "前回取得時間 : ${zdt.format(fmt)}"
        } ?: "前回取得時間 : -"

        // ★ 取得間隔は実測ではなく「設定値」を表示
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
            NotificationCompat.Builder(this)
                .setSmallIcon(smallIconRes)
                .setContentTitle("位置取得を実行中")
                .setContentText("$lastText  |  $intervalText")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$lastText\n$intervalText"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }

    private fun formatInterval(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return when {
            h > 0  -> "${h}時間${m}分${ss}秒"
            m > 0  -> "${m}分${ss}秒"
            else   -> "${ss}秒"
        }
    }

    /** 位置更新開始：updateIntervalMs を使って購読開始 */
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCb: com.google.android.gms.location.LocationCallback? = null

    private suspend fun startLocationUpdatesSafely() {
        if (!::fusedClient.isInitialized) {
            fusedClient = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(this)
        }

        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            updateIntervalMs
        ).build()

        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                val loc = r.lastLocation ?: return

                // 前回取得時刻を更新（通知の1行目に使う）
                val now = System.currentTimeMillis()
                lastFixMillis = now

                // 通知更新：取得間隔は設定値を表示
                updateStatusNotification(now)

                // DB 保存などはサービススコープで
                serviceScope.launch {
                    val st = com.mapconductor.plugin.provider.geolocation.util
                        .BatteryStatusReader.read(applicationContext)

                    val providerName = loc.provider // "fused" / "gps" / "network" など

                    val dao = com.mapconductor.plugin.provider.geolocation.core.data.room
                        .AppDatabase.get(applicationContext)
                        .locationSampleDao()

                    dao.insert(
                        com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            accuracy = loc.accuracy,
                            provider = providerName,
                            batteryPct = st.percent,
                            isCharging = st.isCharging,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        locationCb = cb
        fusedClient.requestLocationUpdates(req, cb, mainLooper)
    }

    /** 位置更新停止 */
    private fun stopLocationUpdatesSafely() {
        locationCb?.let { cb ->
            fusedClient.removeLocationUpdates(cb)
            locationCb = null
        }
    }
}
