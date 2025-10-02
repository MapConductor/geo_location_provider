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

class GeoLocationService : Service() {

    companion object {
        // 既存の START/STOP
        const val ACTION_START = "ACTION_START_LOCATION"
        const val ACTION_STOP  = "ACTION_STOP_LOCATION"

        // ★ VM が使う更新インターバル更新用 Action / Extra を追加
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS        = "EXTRA_UPDATE_MS"

        private const val CHANNEL_ID = "geo_location"
        private const val NOTIF_ID   = 1001
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }

    /** サービス用コルーチンスコープ（終了時に cancel） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** サービスを外部へ返す Binder */
    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }
    private val binder = LocalBinder()

    /** 現在の更新インターバル（ms）。VM 初期化の既定値=5秒 */
    @Volatile
    private var updateIntervalMs: Long = 5_000L

    /** VM から参照される Getter */
    fun getUpdateIntervalMs(): Long = updateIntervalMs

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                val notif = buildNotification()
                // 5秒ルール：入室直後に startForeground
                startForeground(NOTIF_ID, notif)
                _running.value = true

                // 重処理はバックグラウンドで
                serviceScope.launch(Dispatchers.IO) {
                    startLocationUpdatesSafely()
                }
            }

            ACTION_UPDATE_INTERVAL -> {
                // ★ VM からの更新指示を反映
                val newMs = intent.getLongExtra(EXTRA_UPDATE_MS, -1L)
                if (newMs > 0L) {
                    updateIntervalMs = newMs
                    // 実運用では軽量に更新できるなら差し替え、難しければ再購読
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            stopLocationUpdatesSafely()
                        } finally {
                            startLocationUpdatesSafely()
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

    /** Android O+ では事前にチャンネル作成 */
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

    /** 前面通知（最小構成） */
    private fun buildNotification(): Notification {
        // ic_stat_name が未作成なら一旦 Android 標準アイコンでOK
        val smallIconRes = android.R.drawable.ic_menu_mylocation
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIconRes)
                .setContentTitle("GeoLocation running")
                .setContentText("Collecting location (every ${updateIntervalMs/1000}s)")
                .setOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(this)
                .setSmallIcon(smallIconRes)
                .setContentTitle("GeoLocation running")
                .setContentText("Collecting location (every ${updateIntervalMs/1000}s)")
                .setOngoing(true)
                .build()
        }
    }

    /** 位置更新開始：あなたの実装に置き換えてください（updateIntervalMs を使う） */
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCb: com.google.android.gms.location.LocationCallback? = null

    private suspend fun saveSample(
        lat: Double,
        lon: Double,
        acc: Float,
        provider: String?
    ) {
        val dao = com.mapconductor.plugin.provider.geolocation.core.data.room
            .AppDatabase.get(applicationContext)
            .locationSampleDao()

        // 例: バッテリー状態の取得（既存ヘルパがあればそちらを使用）
        val batteryPct = 0   // TODO: 実装に合わせて取得
        val isCharging = false // TODO: 実装に合わせて取得

        dao.insert(
            com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample(
                lat = lat,
                lon = lon,
                accuracy = acc,
                provider = provider,
                batteryPct = batteryPct,
                isCharging = isCharging,
                createdAt = System.currentTimeMillis()
            )
        )
    }

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

                serviceScope.launch {
                    // ★ Battery
                    val st = com.mapconductor.plugin.provider.geolocation.util
                        .BatteryStatusReader.read(applicationContext)

                    // ★ Provider（android.location.Location の provider をそのまま使用）
                    val providerName = loc.provider  // 例: "fused" / "gps" / "network"

                    // ★ DB INSERT（必ず provider / battery を埋める）
                    val dao = com.mapconductor.plugin.provider.geolocation.core.data.room
                        .AppDatabase.get(applicationContext)
                        .locationSampleDao()

                    dao.insert(
                        com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            accuracy = loc.accuracy,
                            provider = providerName,           // ← ここが空だとUIで "-" になります
                            batteryPct = st.percent,          // ← 0 ではなく実値に
                            isCharging = st.isCharging,       // ← 充電中/非充電 を判定
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        locationCb = cb
        fusedClient.requestLocationUpdates(req, cb, mainLooper)
    }

    /** 位置更新停止：あなたの実装に置き換えてください */
    private fun stopLocationUpdatesSafely() {
        locationCb?.let { cb ->
            fusedClient.removeLocationUpdates(cb)
            locationCb = null
        }
    }
}
