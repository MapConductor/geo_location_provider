package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.storageservice.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max

/**
 * Interval(秒) と DR予測間隔(秒) を管理し、保存とサービス反映を行う VM。
 * - 既定: Interval=30秒 / DR間隔=5秒（移行ロジックで算出 or 最小値）
 * - DR間隔は [最小, 上限=floor(GPS/2)] を満たすときのみ保存
 * - サービスへの反映:
 *    - Interval(秒) は DataStore 経由で Service に伝播（ACTION_UPDATE_INTERVAL は補助）
 *    - DR間隔(秒) は ACTION_UPDATE_DR_INTERVAL(sec) を送る
 */
class IntervalSettingsViewModel(
    private val appContext: Context
) : ViewModel() {

    // Repository のルールに合わせた最小値（1秒）
    private companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MIN_DR_INTERVAL_SEC = 1
    }

    private val _secondsText = MutableStateFlow("30")
    val secondsText: StateFlow<String> = _secondsText.asStateFlow()

    private val _drIntervalText = MutableStateFlow("5")
    val drIntervalText: StateFlow<String> = _drIntervalText.asStateFlow()

    private val _imuAvailable = MutableStateFlow(false)
    val imuAvailable: StateFlow<Boolean> = _imuAvailable.asStateFlow()

    init {
        // 既存値を DataStore (SettingsRepository) から読み込み
        viewModelScope.launch(Dispatchers.IO) {
            val gpsSec = (SettingsRepository.currentIntervalMs(appContext) / 1000L).toInt()
                .coerceAtLeast(MIN_INTERVAL_SEC)
            val drSec = SettingsRepository.currentDrIntervalSec(appContext)
                .coerceAtLeast(MIN_DR_INTERVAL_SEC)

            _secondsText.value = gpsSec.toString()
            _drIntervalText.value = drSec.toString()

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

    fun onDrIntervalChanged(text: String) {
        if (text.isEmpty() || text.all { it.isDigit() }) _drIntervalText.value = text
    }

    /**
     * 保存 + サービス適用。
     * - Interval(秒) は常に保存（最小1秒に矯正）
     * - DR間隔(秒) は [最小, 上限=floor(GPS/2)] を満たす場合のみ保存
     * - サービスへは各アクションで個別に反映
     */
    fun saveAndApply() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) GPS Interval 保存 + 反映
            val sec = _secondsText.value.toIntOrNull() ?: 30
            val clampedSec = max(MIN_INTERVAL_SEC, sec)
            val ms = clampedSec * 1000L
            SettingsRepository.setIntervalSec(appContext, clampedSec)
            applyIntervalToService(ms)

            // 2) DR Interval 検証 → 保存 + 反映 or ロールバック
            val drInterval = _drIntervalText.value.toIntOrNull()
            if (drInterval == null) {
                rollbackDrIntervalWithToast("数値を入力してください")
                return@launch
            }

            val gpsInterval = clampedSec
            val upper = floor(gpsInterval / 2.0).toInt()        // 上限
            val lower = MIN_DR_INTERVAL_SEC                     // 下限=1
            if (upper < lower) {
                rollbackDrIntervalWithToast(
                    "現在のGPS間隔ではDR間隔を設定できません。GPSは最小2秒以上にしてください。"
                )
                return@launch
            }

            if (drInterval < lower || drInterval > upper) {
                rollbackDrIntervalWithToast(
                    "DR予測間隔は1秒以上、かつGPS間隔の半分以下にしてください（上限: ${upper}秒）。"
                )
                return@launch
            }

            // 保存 + サービス適用
            SettingsRepository.setDrIntervalSec(appContext, drInterval)
            applyDrIntervalToService(drInterval)

            // 成功トースト
            launchToast(
                "設定を保存して適用しました\n" +
                    "GPS間隔: ${gpsInterval}秒 / DR間隔: ${drInterval}秒"
            )
        }
    }

    private suspend fun rollbackDrIntervalWithToast(message: String) {
        val saved = SettingsRepository.currentDrIntervalSec(appContext)
        _drIntervalText.value = saved.toString()
        launchToast(message)
    }

    private fun launchToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyIntervalToService(ms: Long) {
        // Interval は SettingsRepository 経由で Service 側に伝播されるため、
        // ここでは明示的な ACTION_UPDATE_INTERVAL は行わない。
        // service が未起動の場合のみ、従来どおり起動だけ行う。
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_START
        }
        appContext.startService(intent)
    }

    private fun applyDrIntervalToService(sec: Int) {
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_DR_INTERVAL
            putExtra(GeoLocationService.EXTRA_DR_INTERVAL_SEC, sec)
        }
        appContext.startService(intent)
    }
}
