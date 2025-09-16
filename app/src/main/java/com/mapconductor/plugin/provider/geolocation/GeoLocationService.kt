package com.mapconductor.plugin.provider.geolocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GeoLocationService : Service() {

    // 実行状態（UIトグルと厳密同期）
    companion object {
        private const val TAG = "GeoLocationService"
        private const val CHANNEL_ID = "geo_location_service"
        private const val NOTIF_ID = 1001

        const val ACTION_UPDATE_INTERVAL = "GeoLocationService.ACTION_UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS = "extra_update_ms"

        val running = MutableStateFlow(false)
    }

    // バッテリー情報
    data class BatteryInfo(val pct: Int, val isCharging: Boolean)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // UI から bind して観測できるフロー
    private val _locationFlow = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> get() = _locationFlow

    private val _batteryFlow = MutableStateFlow<BatteryInfo?>(null)
    val batteryFlow: StateFlow<BatteryInfo?> get() = _batteryFlow

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var updateIntervalMs: Long = 5_000L

    // DB
    private lateinit var db: AppDatabase

    // bind 用
    inner class LocalBinder : android.os.Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification("Waiting for location…"))
        fused = LocationServices.getFusedLocationProviderClient(this)
        db = AppDatabase.get(this)
        running.value = true
        _batteryFlow.value = getBatteryInfo()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 常駐通知（スワイプで消せない）
        startForeground(NOTIF_ID, buildOngoingNotification())

        // ← ここを追記：更新間隔の反映
        if (intent?.action == ACTION_UPDATE_INTERVAL) {
            val requested = intent.getLongExtra(EXTRA_UPDATE_MS, updateIntervalMs)
            val clamped = requested.coerceIn(1_000L, 3_600_000L) // 1秒〜1時間
            if (clamped != updateIntervalMs) {
                updateIntervalMs = clamped
                restartLocationUpdates()

                // 表示テキストを更新しておく（任意）
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildOngoingNotification())
            }
        }

        // 可能な限り再起動させる
        return START_STICKY
    }

    private fun buildOngoingNotification(): Notification {
        // 既存のチャンネルID/タイトル/アイコンを流用しつつ、
        // builder.setOngoing(true) を必ず指定。
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoLocation tracking")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)                  // ★ スワイプで消せない
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        serviceScope.cancel()
        running.value = false
        super.onDestroy()
    }

    private fun restartLocationUpdates() {
        stopLocationUpdates()
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        // 権限は UI で取得済み想定だが、念のため安全チェック
        val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission missing. Stopping service.")
            stopSelf()
            return
        }

        // 即時表示
        fused.lastLocation.addOnSuccessListener { it?.let(::onNewLocation) }

        // リクエスト生成（可変間隔）
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            updateIntervalMs
        )
            // 「最短」を基準間隔と同じにして、速すぎる丸めを避ける
            .setMinUpdateIntervalMillis(updateIntervalMs)
            // バッチング（遅延まとめ配信）をしない
            // .setMaxUpdateDelayMillis(0) ← 省略 or 0 を指定（0 と同等）
            // 移動距離しきい値をゼロ（時間ベースで配信）
            .setMinUpdateDistanceMeters(0f)
            // 許可レベルに応じたグラニュラリティ
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            // 初回から“高精度 fix まで待たない”（必要なら true に）
            .setWaitForAccurateLocation(false)
            .build()


        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::onNewLocation)
            }
        }

        fused.requestLocationUpdates(
            request,
            callback as LocationCallback,
            mainLooper
        )
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private var lastTs = 0L
    private fun onNewLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val dt = if (lastTs == 0L) -1 else now - lastTs
        lastTs = now
        Log.d(TAG, "tick dt=${dt}ms (request=${updateIntervalMs}ms) provider=${loc.provider}")

        val batt = getBatteryInfo()

        // 1) Flow 更新
        _locationFlow.value = loc
        _batteryFlow.value = batt

        // 2) 通知更新
        val text = buildString {
            append("lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}m")
            if (batt.pct >= 0) {
                append(" | battery=${batt.pct}%")
                if (batt.isCharging) append(" (充電中)")
            }
        }
        notifyForeground(text)

        // 3) DB 保存（無制限で蓄積）
        serviceScope.launch(Dispatchers.IO) {
            db.locationSampleDao().insert(
                LocationSample(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracy = loc.accuracy,
                    provider = loc.provider,
                    batteryPct = batt.pct,
                    isCharging = batt.isCharging,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        Log.d(TAG, "location: $text")
    }

    private fun notifyForeground(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(content))
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GeoLocationService", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoLocationProvider")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    // バッテリー情報の即時取得（追加権限不要）
    private fun getBatteryInfo(): BatteryInfo {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(pct, isCharging)
    }
}
