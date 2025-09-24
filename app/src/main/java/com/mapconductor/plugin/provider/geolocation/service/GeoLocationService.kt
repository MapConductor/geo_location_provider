package com.mapconductor.plugin.provider.geolocation.service

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.mapconductor.plugin.provider.geolocation.SettingsStore
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow

private const val CHANNEL_ID = "geo_location_service"   // 通知チャネルID
private const val NOTIF_ID   = 1001                     // 通知ID（任意の固定整数）
private const val TAG = "GeoLocationService"

class GeoLocationService : Service() {
    // Fused Location 関連
    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    // 前景化・測位の状態
    private var isForegroundStarted = false
    private var locationStarted = false

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
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // 1) 5秒ルール厳守：まず前景化
                if (!isForegroundStarted) {
                    startForeground(NOTIF_ID, buildNotification("Starting…"))
                    isForegroundStarted = true
                    Log.i(TAG, "startForeground done")
                }
                // 2) 測位開始（冪等）
                if (!locationStarted) {
                    startLocationUpdates()
                    Log.i(TAG, "location start requested")
                }
                running.value = true
            }

            ACTION_UPDATE_INTERVAL -> {
                val requested = intent.getLongExtra(EXTRA_UPDATE_MS, updateIntervalMs)
                val clamped = requested.coerceIn(MIN_UPDATE_MS, MAX_UPDATE_MS)
                if (clamped != updateIntervalMs) {
                    updateIntervalMs = clamped
                    restartLocationUpdates()
                    serviceScope.launch { settingsStore.setUpdateIntervalMs(updateIntervalMs) }
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received; stopping")
                stopLocationUpdates()
                stopSelf()
            }

            else -> {
                // 端末やプロセスの再生成で null のことがある。保険で前景化→開始
                if (!isForegroundStarted) {
                    startForeground(NOTIF_ID, buildNotification("Resuming…"))
                    isForegroundStarted = true
                }
                if (!locationStarted) startLocationUpdates()
            }
        }

        return START_STICKY
    }

    private fun buildOngoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoLocation tracking")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
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
        Log.i(TAG, "startLocationUpdates begin")

        // 1) 権限チェック
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "perm fine=$hasFine coarse=$hasCoarse")
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "No location permission; stopSelf()")
            notifyForeground("位置権限がありません")
            stopSelf()
            return
        }

        // 2) 直近位置を即時反映（成功可否に関係なくできる範囲で）
        fused.lastLocation.addOnSuccessListener { it?.let(::onNewLocation) }

        // 3) リクエスト生成（可変間隔）
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            updateIntervalMs
        )
            .setMinUpdateIntervalMillis(updateIntervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setWaitForAccurateLocation(false)
            .build()

        // 4) 端末の位置設定（GPS/位置情報）が有効かを事前チェック
        val settingsReq = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .build()
        val settingsClient = LocationServices.getSettingsClient(this)

        settingsClient.checkLocationSettings(settingsReq)
            .addOnSuccessListener {
                // 5) 設定が満たされている → 更新開始
                if (callback == null) {
                    callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            result.lastLocation?.let(::onNewLocation)
                        }
                    }
                }
                try {
                    fused.requestLocationUpdates(
                        request,
                        callback as LocationCallback,
                        mainLooper
                    )
                    locationStarted = true
                    Log.i(TAG, "requestLocationUpdates issued")
                } catch (se: SecurityException) {
                    Log.e(TAG, "SecurityException in requestLocationUpdates", se)
                    notifyForeground("位置権限エラー")
                    stopSelf()
                }
            }
            .addOnFailureListener { e ->
                // 位置設定が無効（GPS OFF 等）のケース
                Log.w(TAG, "Location settings not satisfied: ${e.message}")
                notifyForeground("端末の位置設定が無効です（GPS/位置情報をONにしてください）")
                // 開始しない＝locationStarted は false のまま
            }
    }

    private fun stopLocationUpdates() {
        if (locationStarted && callback != null) {
            runCatching { fused.removeLocationUpdates(callback as LocationCallback) }
                .onFailure { Log.w(TAG, "removeLocationUpdates failed", it) }
        }
        callback = null
        locationStarted = false
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
            .setSmallIcon(R.drawable.ic_menu_mylocation)
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
