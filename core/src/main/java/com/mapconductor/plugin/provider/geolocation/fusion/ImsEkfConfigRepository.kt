package com.mapconductor.plugin.provider.geolocation.fusion

import android.content.Context

/**
 * SharedPreferences-backed persistence for IMS EKF tuning.
 *
 * This keeps wiring simple for the sample app while allowing settings to
 * survive process restarts.
 */
object ImsEkfConfigRepository {
    private const val PREFS_NAME = "ims_ekf_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USE_CASE = "use_case"
    private const val KEY_ALLOWED_LATENCY_MS = "allowed_latency_ms"
    private const val KEY_GPS_INTERVAL_OVERRIDE_MS = "gps_interval_override_ms"

    fun get(context: Context): ImsEkfConfig {
        val prefs =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val useCaseRaw = prefs.getString(KEY_USE_CASE, ImsUseCase.WALK.name) ?: ImsUseCase.WALK.name
        val useCase = runCatching { ImsUseCase.valueOf(useCaseRaw) }.getOrElse { ImsUseCase.WALK }

        val base = ImsEkfConfig.defaults(useCase)
        val allowedLatencyMs = prefs.getLong(KEY_ALLOWED_LATENCY_MS, base.allowedLatencyMs)
        val overrideMsRaw = prefs.getLong(KEY_GPS_INTERVAL_OVERRIDE_MS, 0L)
        val gpsOverride = overrideMsRaw.takeIf { it > 0L }

        return ImsEkfConfig.clamp(
            base.copy(
                enabled = enabled,
                allowedLatencyMs = allowedLatencyMs,
                gpsIntervalMsOverride = gpsOverride
            )
        )
    }

    fun set(context: Context, config: ImsEkfConfig) {
        val cfg = ImsEkfConfig.clamp(config)
        val prefs =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_USE_CASE, cfg.useCase.name)
            .putLong(KEY_ALLOWED_LATENCY_MS, cfg.allowedLatencyMs)
            .putLong(KEY_GPS_INTERVAL_OVERRIDE_MS, cfg.gpsIntervalMsOverride ?: 0L)
            .apply()
    }
}

