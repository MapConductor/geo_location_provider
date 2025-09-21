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
import kotlinx.coroutines.launch
import android.os.Binder
import kotlinx.coroutines.flow.firstOrNull
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import kotlinx.coroutines.flow.MutableStateFlow

private const val CHANNEL_ID = "geo_location_service"   // 通知チャネルID
private const val NOTIF_ID   = 1001                     // 通知ID（任意の固定整数）
private const val TAG = "GeoLocationService"

class GeoLocationService : Service() {
    // Fused Location 関連
    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    // DB
    private lateinit var db: AppDatabase

    // ライブ状態
    private val _locationFlow: MutableStateFlow<Location?> = MutableStateFlow(null)
    private val _batteryFlow: MutableStateFlow<BatteryInfo> = MutableStateFlow(BatteryInfo(-1, false))

    companion object {
        const val ACTION_UPDATE_INTERVAL = "com.mapconductor.UPDATE_INTERVAL"
        const val EXTRA_UPDATE_MS        = "extra_update_ms"
        const val ACTION_START           = "com.mapconductor.plugin.provider.geolocation.action.START"
        const val ACTION_STOP            = "com.mapconductor.plugin.provider.geolocation.action.STOP"

        private const val MIN_UPDATE_MS  = 5_000L       // ★ 最短5秒
        private const val MAX_UPDATE_MS  = 3_600_000L   //   最長1時間
        val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    /** 現在の更新間隔（ms）。起動時は 5秒 で初期化 */
    private var updateIntervalMs: Long = MIN_UPDATE_MS

    /** Binder: UI から現在値を参照させる */
    inner class LocalBinder : Binder() {
        fun getService(): GeoLocationService = this@GeoLocationService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    /** UI から参照される Getter（秒ではなく ms を返す） */
    fun getUpdateIntervalMs(): Long = updateIntervalMs

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        fused = LocationServices.getFusedLocationProviderClient(this)
        db = AppDatabase.get(this)
        running.value = true
        _batteryFlow.value = getBatteryInfo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("GeoLocationService", "onStartCommand action=${intent?.action}")
        // ① 起動時：DataStore の保存値があれば反映
        if (intent == null) {
            serviceScope.launch {
                val saved = settingsStore.updateIntervalMsFlow.firstOrNull()
                if (saved != null && saved != updateIntervalMs) {
                    updateIntervalMs = saved.coerceIn(MIN_UPDATE_MS, MAX_UPDATE_MS)
                    restartLocationUpdates()
                }
                // SSOT を常に正に（起動時の現在値を書き戻し）
                settingsStore.setUpdateIntervalMs(updateIntervalMs)
            }
        }

        // ② 更新要求（UIから）
        if (intent?.action == ACTION_UPDATE_INTERVAL) {
            val requested = intent.getLongExtra(EXTRA_UPDATE_MS, updateIntervalMs)
            val clamped = requested.coerceIn(MIN_UPDATE_MS, MAX_UPDATE_MS)
            if (clamped != updateIntervalMs) {
                updateIntervalMs = clamped
                restartLocationUpdates()
                // 書き戻し（SSOT）
                serviceScope.launch { settingsStore.setUpdateIntervalMs(updateIntervalMs) }
            }
        }

        if (intent?.action == ACTION_START) {
            startForeground(NOTIF_ID, buildNotification("Waiting for location…"))
            startLocationUpdates()
        }

        return START_STICKY
    }

    private fun buildOngoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoLocation tracking")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
            .setMinUpdateIntervalMillis(updateIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
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

data class BatteryInfo(val pct: Int, val isCharging: Boolean)
