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
import com.mapconductor.plugin.provider.geolocation.util.ServiceStateIndicator
import com.mapconductor.plugin.provider.storageservice.prefs.SettingsRepository
import com.mapconductor.plugin.provider.storageservice.prefs.DrMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max

/**
 * ViewModel that manages Interval (GPS interval in seconds) and DR interval in seconds.
 *
 * - Defaults: Interval = 30 sec, DR interval = 5 sec (from migration or minimum values).
 * - DR interval is saved only when it satisfies [min, max = floor(GPS/2)].
 * - Propagation to service:
 *   - Interval (sec) is sent via SettingsRepository (ACTION_UPDATE_INTERVAL acts as helper).
 *   - DR interval (sec) is sent via ACTION_UPDATE_DR_INTERVAL.
 */
class IntervalSettingsViewModel(
    private val appContext: Context
) : ViewModel() {

    // Minimums consistent with SettingsRepository
    private companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MIN_DR_INTERVAL_SEC = 0           // 0 means "DR disabled"
        const val MIN_ENABLED_DR_INTERVAL_SEC = 1   // Minimum when DR is enabled
    }

    private val _secondsText = MutableStateFlow("30")
    val secondsText: StateFlow<String> = _secondsText.asStateFlow()

    private val _drIntervalText = MutableStateFlow("5")
    val drIntervalText: StateFlow<String> = _drIntervalText.asStateFlow()

    private val _drGpsIntervalText = MutableStateFlow("0")
    val drGpsIntervalText: StateFlow<String> = _drGpsIntervalText.asStateFlow()

    private val _drMode = MutableStateFlow(DrMode.Prediction)
    val drMode: StateFlow<DrMode> = _drMode.asStateFlow()

    private val _imuAvailable = MutableStateFlow(false)
    val imuAvailable: StateFlow<Boolean> = _imuAvailable.asStateFlow()

    init {
        // Load existing values from DataStore (SettingsRepository)
        viewModelScope.launch(Dispatchers.IO) {
            val gpsSec = (SettingsRepository.currentIntervalMs(appContext) / 1000L).toInt()
                .coerceAtLeast(MIN_INTERVAL_SEC)
            val drSec = SettingsRepository.currentDrIntervalSec(appContext)
            val drGpsSec = SettingsRepository.currentDrGpsIntervalSec(appContext)
            val mode = SettingsRepository.currentDrMode(appContext)

            _secondsText.value = gpsSec.toString()
            _drIntervalText.value = drSec.toString()
            _drGpsIntervalText.value = drGpsSec.toString()
            _drMode.value = mode

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

    fun onDrGpsIntervalChanged(text: String) {
        if (text.isEmpty() || text.all { it.isDigit() }) _drGpsIntervalText.value = text
    }

    fun onDrModeChanged(mode: DrMode) {
        _drMode.value = mode
    }

    /**
     * Save settings and apply them to the service.
     *
     * - Interval (sec) is always saved (clamped to minimum 1).
     * - DR interval is saved only when it satisfies [min, max = floor(GPS/2)].
     * - Also sends actions to apply to the running service.
     */
    fun saveAndApply() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) Save GPS Interval and apply
            val sec = _secondsText.value.toIntOrNull() ?: 30
            val clampedSec = max(MIN_INTERVAL_SEC, sec)
            val ms = clampedSec * 1000L
            SettingsRepository.setIntervalSec(appContext, clampedSec)
            applyIntervalToService(ms)

            // 1.5) Save DR mode (applies when DR is enabled).
            SettingsRepository.setDrMode(appContext, _drMode.value)

            // 2) Validate and save DR interval, or roll back with toast
            val drInterval = _drIntervalText.value.toIntOrNull()
            if (drInterval == null) {
                rollbackDrIntervalWithToast("Please enter a numeric value.")
                return@launch
            }

            val gpsInterval = clampedSec
            val upper = floor(gpsInterval / 2.0).toInt()        // max bound when enabled

            // Special case: 0 means "DR disabled".
            if (drInterval == 0) {
                SettingsRepository.setDrIntervalSec(appContext, 0)
                applyDrIntervalToService(0)
                if (!saveDrGpsIntervalIfNeeded(gpsInterval)) {
                    return@launch
                }
                launchToast(
                    "Settings saved.\n" +
                        "GPS interval: ${gpsInterval} sec / DR: disabled (GPS only)"
                )
                return@launch
            }

            val lower = MIN_ENABLED_DR_INTERVAL_SEC             // enabled min = 1
            if (upper < lower) {
                rollbackDrIntervalWithToast(
                    "With the current GPS interval DR cannot be enabled. Set GPS interval to at least 2 seconds."
                )
                return@launch
            }

            if (drInterval < lower || drInterval > upper) {
                rollbackDrIntervalWithToast(
                    "DR interval must be >= 1 sec and <= half of GPS interval (max: $upper sec)."
                )
                return@launch
            }

            // Save and apply DR interval
            SettingsRepository.setDrIntervalSec(appContext, drInterval)
            applyDrIntervalToService(drInterval)

            if (!saveDrGpsIntervalIfNeeded(gpsInterval)) {
                return@launch
            }

            // Success toast
            launchToast(
                "Settings saved and applied.\n" +
                    "GPS interval: ${gpsInterval} sec / DR interval: ${drInterval} sec"
            )
        }
    }

    private suspend fun saveDrGpsIntervalIfNeeded(gpsIntervalSec: Int): Boolean {
        if (_drMode.value == DrMode.Completion) {
            return true
        }

        val v = _drGpsIntervalText.value.toIntOrNull()
        if (v == null) {
            rollbackDrGpsIntervalWithToast("Please enter a numeric value.")
            return false
        }

        // 0 means "every GPS fix to DR" (legacy behavior).
        if (v == 0) {
            SettingsRepository.setDrGpsIntervalSec(appContext, 0)
            applyDrGpsIntervalToService(0)
            return true
        }

        if (v < gpsIntervalSec) {
            rollbackDrGpsIntervalWithToast(
                "DR GPS interval must be 0 or >= GPS interval (min: $gpsIntervalSec sec)."
            )
            return false
        }

        SettingsRepository.setDrGpsIntervalSec(appContext, v)
        applyDrGpsIntervalToService(v)
        return true
    }

    private suspend fun rollbackDrIntervalWithToast(message: String) {
        val saved = SettingsRepository.currentDrIntervalSec(appContext)
        _drIntervalText.value = saved.toString()
        launchToast(message)
    }

    private suspend fun rollbackDrGpsIntervalWithToast(message: String) {
        val saved = SettingsRepository.currentDrGpsIntervalSec(appContext)
        _drGpsIntervalText.value = saved.toString()
        launchToast(message)
    }

    private fun launchToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun sendToRunningService(intent: Intent) {
        val running = withContext(Dispatchers.Main) {
            ServiceStateIndicator.isRunning(appContext, GeoLocationService::class.java)
        }
        if (!running) return
        appContext.startService(intent)
    }

    private suspend fun applyIntervalToService(ms: Long) {
        // Interval is persisted via SettingsRepository.
        // If the service is already running, request it to apply the new value.
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_INTERVAL
            putExtra(GeoLocationService.EXTRA_UPDATE_MS, ms)
        }
        sendToRunningService(intent)
    }

    private suspend fun applyDrIntervalToService(sec: Int) {
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_DR_INTERVAL
            putExtra(GeoLocationService.EXTRA_DR_INTERVAL_SEC, sec)
        }
        sendToRunningService(intent)
    }

    private suspend fun applyDrGpsIntervalToService(sec: Int) {
        val intent = Intent(appContext, GeoLocationService::class.java).apply {
            action = GeoLocationService.ACTION_UPDATE_DR_GPS_INTERVAL
            putExtra(GeoLocationService.EXTRA_DR_GPS_INTERVAL_SEC, sec)
        }
        sendToRunningService(intent)
    }
}
