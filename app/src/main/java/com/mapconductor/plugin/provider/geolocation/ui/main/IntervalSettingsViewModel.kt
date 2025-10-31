package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.SettingsStore
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新間隔(秒) と 予測回数 を管理する VM。
 * - 既定: Interval=30秒 / 予測回数=5回
 * - IMU有効かつ stepSec(=sec/(count+1)) < 5秒 なら 予測回数の保存は拒否（現在値継続）
 * - Interval は従来どおり GeoLocationService へ適用（※適用トリガは DataStore 保存 or 明示 Intent）
 */
class IntervalSettingsViewModel(private val appContext: Context) : ViewModel() {

    private val settingsStore = SettingsStore(appContext)

    private val _seconds = MutableStateFlow("30")
    val seconds: StateFlow<String> = _seconds

    private val _predictCount = MutableStateFlow("5")
    val predictCount: StateFlow<String> = _predictCount

    // ---- GeoLocationService バインド（必要なら反射で取得） ----
    private var connectJob: Job? = null
    @Volatile private var bound = false
    @Volatile private var service: GeoLocationService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // LocalBinder の実装差異に依存しないよう反射で getService() を試す
            val s: GeoLocationService? = try {
                val m = binder?.javaClass?.getMethod("getService")
                @Suppress("UNCHECKED_CAST")
                m?.invoke(binder) as? GeoLocationService
            } catch (_: Throwable) {
                null
            }
            service = s
            bound = s != null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    init {
        // 既存保存値のロード：なければ 30秒/5回
        viewModelScope.launch(Dispatchers.IO) {
            val ms = settingsStore.updateIntervalMsFlow.first()
            val sec = ((ms ?: 30_000L) / 1000L).toInt().coerceAtLeast(0)
            val pc = (settingsStore.predictCountFlow.first() ?: 5).coerceAtLeast(0)
            _seconds.value = sec.toString()
            _predictCount.value = pc.toString()
        }

        // サービス起動＆バインド（サービス側が DataStore を監視していれば必須ではないが、従来踏襲）
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            val intent = Intent(appContext, GeoLocationService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
    }

    fun onSecondsChanged(text: String) {
        _seconds.value = text.filter { it.isDigit() }.take(5)
    }

    fun onPredictCountChanged(text: String) {
        _predictCount.value = text.filter { it.isDigit() }.take(4)
    }

    /** 端末が IMU(加速度+ジャイロ) を使えるかどうか */
    private fun isImuCapable(): Boolean {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasAcc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        return hasAcc && hasGyro
    }

    /**
     * 保存＋サービス適用。
     * - Interval（秒）は常に保存
     * - 予測回数は、IMU有効かつ stepSec < 5秒 のときは保存拒否（現在値継続、Toast警告）
     * - サービスへの適用は DataStore 監視で自動 / もしくは Intent で明示
     */
    fun saveAndApply() {
        viewModelScope.launch(Dispatchers.IO) {
            val sec = _seconds.value.toIntOrNull() ?: return@launch
            val count = _predictCount.value.toIntOrNull() ?: 0

            // Interval は保存
            settingsStore.setUpdateIntervalMs(sec.coerceAtLeast(0) * 1000L)

            // 予測回数の検証（IMU 有効時のみ）
            val imuOk = isImuCapable()
            val stepSec = sec.toFloat() / (count + 1).coerceAtLeast(1)
            val canSavePredict = !(imuOk && stepSec < 5f)
            if (canSavePredict) {
                settingsStore.setPredictCount(count.coerceAtLeast(0))
            }

            // --- サービス適用のトリガ ---
            // 1) DataStore 監視型なら、上記保存のみでOK
            // 2) 明示適用が必要なら、Intent を投げる（サービス側で受け取る実装を用意してください）
            try {
                val applyIntent = Intent(appContext, GeoLocationService::class.java).apply {
                    action = "com.mapconductor.plugin.provider.APPLY_SETTINGS"
                    putExtra("updateIntervalMs", (sec.coerceAtLeast(0) * 1000L))
                    putExtra("predictCount", count.coerceAtLeast(0))
                }
                ContextCompat.startForegroundService(appContext, applyIntent)
            } catch (_: Throwable) {
                // サービス側が未対応でもクラッシュしない
            }

            withContext(Dispatchers.Main) {
                if (!canSavePredict) {
                    Toast.makeText(
                        appContext,
                        "予測間隔が5秒未満になるため、予測回数は反映しません（現在の設定で継続）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        appContext,
                        "更新間隔を ${sec}秒 / 予測回数を ${count} に設定しました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            appContext.unbindService(conn)
            bound = false
        }
        connectJob?.cancel()
    }
}
