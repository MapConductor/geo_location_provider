package com.mapconductor.plugin.provider.geolocation

import android.app.*
import android.content.Intent
import android.content.IntentFilter // ★ 追加
import android.location.Location
import android.os.BatteryManager   // ★ 追加
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.* // FusedLocationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GeoLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // UI から bind して観測できるよう公開（最新の位置）
    private val _locationFlow = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> get() = _locationFlow

    // ★ バッテリー情報も公開（任意でUIから購読可能）
    data class BatteryInfo(val pct: Int, val isCharging: Boolean)
    private val _batteryFlow = MutableStateFlow<BatteryInfo?>(null)
    val batteryFlow: StateFlow<BatteryInfo?> get() = _batteryFlow

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    // bind 用（任意。UI で状態を読むならあると便利）
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

        // ★ 起動時点のバッテリー初期値（任意）
        _batteryFlow.value = getBatteryInfo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
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

        // まずは lastLocation を通知して“即時表示”
        fused.lastLocation.addOnSuccessListener { loc ->
            loc?.let { onNewLocation(it) }
        }

        // 継続更新のリクエスト
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, // 高精度が不要なら PRIORITY_BALANCED_POWER_ACCURACY
            5_000L                           // 要件に応じて調整
        )
            .setMinUpdateIntervalMillis(2_000L)  // 端末が早く取れた時の最短間隔
            .setMaxUpdateDelayMillis(10_000L)    // バッチ遅延上限
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }

        fused.requestLocationUpdates(
            request,
            callback as LocationCallback,
            mainLooper // コールバックスレッド
        )
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private fun onNewLocation(loc: Location) {
        // ★ バッテリー取得（即時）
        val batt = getBatteryInfo()

        // 1) Flow を更新（UI が bind していれば即時反映）
        _locationFlow.value = loc
        _batteryFlow.value = batt // ★ 追加

        // 2) 通知の本文を更新（位置 + バッテリーを表示）
        val text = buildString {
            append("lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}m")
            if (batt.pct >= 0) {
                append(" | battery=${batt.pct}%")
                if (batt.isCharging) append(" (充電中)")
            }
        }
        notifyForeground(text)

        // 3) ログ（“tick...”の代わり）
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

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoLocationProvider")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    // ★ バッテリー情報の即時取得（追加の権限は不要）
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

    companion object {
        private const val TAG = "GeoLocationService"
        private const val CHANNEL_ID = "geo_location_service"
        private const val NOTIF_ID = 1001
    }
}
