package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.SettingsStore
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interval(秒) と 予測回数 を管理し、保存とサービス反映を行う VM。
 * - 既定: Interval=30秒 / 予測回数=5
 * - IMU有効かつ stepSec(=sec/(count+1)) < 5秒 の場合は予測回数の保存を拒否（トースト表示）
 * - Interval のサービス反映は GeoLocationService.ACTION_UPDATE_INTERVAL を発行
 */
class IntervalSettingsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val store = SettingsStore(appContext)

    private val _secondsText = MutableStateFlow("30")
    val secondsText: StateFlow<String> = _secondsText.asStateFlow()

    private val _predictCountText = MutableStateFlow("5")
    val predictCountText: StateFlow<String> = _predictCountText.asStateFlow()

    private val _imuAvailable = MutableStateFlow(false)
    val imuAvailable: StateFlow<Boolean> = _imuAvailable.asStateFlow()

    init {
        // 既存値を DataStore から読み込み（未保存時は既定値が入る）
        viewModelScope.launch(Dispatchers.IO) {
            val sec = (store.currentIntervalMs() / 1000L).toInt()
                .coerceAtLeast(SettingsStore.MIN_INTERVAL_SEC)
            val cnt = store.currentPredictCount()
                .coerceAtLeast(SettingsStore.MIN_PREDICT_COUNT)

            _secondsText.value = sec.toString()
            _predictCountText.value = cnt.toString()

            _imuAvailable.value = detectImuAvailable(appContext)
        }
    }

    private fun detectImuAvailable(ctx: Context): Boolean {
        val sm = ContextCompat.getSystemService(ctx, SensorManager::class.java) ?: return false
        val hasAcc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        return hasAcc && hasGyro
    }

    fun onSecondsChanged(text: String) {
        if (text.isEmpty() || text.all { it.isDigit() }) _secondsText.value = text
    }

    fun onPredictCountChanged(text: String) {
        if (text.isEmpty() || text.all { it.isDigit() }) _predictCountText.value = text
    }

    /**
     * 保存 + サービス適用。
     * - Interval(秒) は常に保存（最小5秒に矯正）
     * - 予測回数は、IMU有効かつ stepSec < 5秒 のとき保存拒否（現在値継続、Toast警告）
     * - サービスへの Interval 反映は ACTION_UPDATE_INTERVAL を送る
     */
    fun saveAndApply() {
        viewModelScope.launch(Dispatchers.IO) {
            val sec = _secondsText.value.toIntOrNull()?.coerceAtLeast(SettingsStore.MIN_INTERVAL_SEC)
                ?: SettingsStore.MIN_INTERVAL_SEC
            _secondsText.value = sec.toString()

            val count = _predictCountText.value.toIntOrNull()
                ?.coerceAtLeast(SettingsStore.MIN_PREDICT_COUNT)
                ?: SettingsStore.MIN_PREDICT_COUNT
            _predictCountText.value = count.toString()

            // Interval を保存 & サービスへ即時反映
            val ms = sec * 1000L
            store.setUpdateIntervalMs(ms)
            applyIntervalToService(ms)

            // 予測回数の保存判定（IMUが使える場合のみ制約を課す）
            val stepSec = sec.toFloat() / (count + 1).toFloat()  // 例: 30 / (5+1) = 5.0
            val allowSavePredict = (!_imuAvailable.value) || stepSec >= SettingsStore.MIN_INTERVAL_SEC.toFloat()

            if (allowSavePredict) {
                store.setPredictCount(count)
                applyPredictToService(count)
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        "予測間隔が5秒未満のため、予測回数の保存をスキップしました。",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // UI を保存済み値に戻す
                val saved = store.currentPredictCount()
                _predictCountText.value = saved.toString()
            }
        }
    }

    private fun applyIntervalToService(ms: Long) {
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_INTERVAL
            putExtra(GeoLocationService.EXTRA_UPDATE_MS, ms)
        }
        appContext.startService(intent)
    }

    private fun applyPredictToService(count: Int) {
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_PREDICT
            putExtra(GeoLocationService.EXTRA_PREDICT_COUNT, count)
        }
        appContext.startService(intent)
    }
}
