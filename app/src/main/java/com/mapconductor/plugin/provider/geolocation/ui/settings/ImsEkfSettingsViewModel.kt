package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.fusion.ImsEkfConfig
import com.mapconductor.plugin.provider.geolocation.fusion.ImsEkfConfigRepository
import com.mapconductor.plugin.provider.geolocation.fusion.ImsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ImsEkfSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext

    private val _config = MutableStateFlow(ImsEkfConfigRepository.get(ctx))
    val config: StateFlow<ImsEkfConfig> = _config

    fun reload() {
        _config.value = ImsEkfConfigRepository.get(ctx)
    }

    fun setEnabled(enabled: Boolean) {
        persist(_config.value.copy(enabled = enabled))
    }

    fun setUseCase(useCase: ImsUseCase) {
        val cur = _config.value
        val next =
            ImsEkfConfig.defaults(useCase).copy(
                enabled = cur.enabled,
                allowedLatencyMs = cur.allowedLatencyMs,
                gpsIntervalMsOverride = cur.gpsIntervalMsOverride
            )
        persist(next)
    }

    fun setAllowedLatencyMs(allowedLatencyMs: Long) {
        persist(_config.value.copy(allowedLatencyMs = allowedLatencyMs))
    }

    fun setGpsIntervalOverrideMs(overrideMs: Long?) {
        persist(_config.value.copy(gpsIntervalMsOverride = overrideMs))
    }

    fun resetDefaults() {
        persist(ImsEkfConfig.defaults(ImsUseCase.WALK))
    }

    private fun persist(config: ImsEkfConfig) {
        val cfg = ImsEkfConfig.clamp(config)
        _config.value = cfg
        viewModelScope.launch(Dispatchers.IO) {
            ImsEkfConfigRepository.set(ctx, cfg)
        }
    }
}

