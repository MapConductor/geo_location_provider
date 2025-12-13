package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import com.mapconductor.plugin.provider.geolocation.prefs.UploadPrefsRepository
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UploadSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UploadPrefsRepository(app)
    private val drivePrefs = DrivePrefsRepository(app)

    val schedule: StateFlow<UploadSchedule> =
        prefs.scheduleFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            UploadSchedule.NIGHTLY
        )

    val intervalSec: StateFlow<Int> =
        prefs.intervalSecFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            0
        )

    val zoneId: StateFlow<String> =
        prefs.zoneIdFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            "Asia/Tokyo"
        )

    // Drive is considered configured when accountEmail is non-blank.
    val driveConfigured: StateFlow<Boolean> =
        drivePrefs.accountEmailFlow
            .map { it.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Upload is effectively enabled only when schedule != NONE and Drive is configured.
    val uploadEnabled: StateFlow<Boolean> =
        combine(schedule, driveConfigured) { s, ready ->
            ready && s != UploadSchedule.NONE
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun setUploadEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!enabled) {
                // Always allow turning upload off.
                prefs.setSchedule(UploadSchedule.NONE)
                return@launch
            }

            val ready = driveConfigured.value
            if (!ready) {
                _status.value =
                    "Drive upload requires sign-in. Please configure Drive in Drive settings."
                return@launch
            }

            val current = schedule.value
            val next = if (current == UploadSchedule.NONE) UploadSchedule.NIGHTLY else current
            prefs.setSchedule(next)
        }
    }

    fun setSchedule(newSchedule: UploadSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            if (newSchedule == UploadSchedule.NONE) {
                prefs.setSchedule(UploadSchedule.NONE)
            } else {
                prefs.setSchedule(newSchedule)
            }
        }
    }

    fun setIntervalSec(sec: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val clamped = when {
                sec < 0      -> 0
                sec > 86_400 -> {
                    _status.value =
                        "Interval must be between 0 and 86400 seconds. For longer periods use nightly backup."
                    86_400
                }
                else         -> sec
            }
            prefs.setIntervalSec(clamped)
        }
    }

    fun setZoneId(zoneId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setZoneId(zoneId)
        }
    }

    fun setStatus(message: String) {
        _status.value = message
    }
}
