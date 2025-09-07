package com.mapconductor.plugin.provider.geolocation

import android.app.*
import android.content.Intent
import android.location.Location
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
        // 1) Flow を更新（UI が bind していれば即時反映）
        _locationFlow.value = loc

        // 2) 通知の本文を更新（ユーザーにも位置を“表示”）
        val text = "lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}m"
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

    companion object {
        private const val TAG = "GeoLocationService"
        private const val CHANNEL_ID = "geo_location_service"
        private const val NOTIF_ID = 1001
    }
}
