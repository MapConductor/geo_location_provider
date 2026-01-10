package com.mapconductor.plugin.provider.geolocation.fusion

import kotlin.math.max

/**
 * Configuration for IMS (IMU + GPS) fusion.
 *
 * Notes:
 * - gpsIntervalMsOverride lets callers supply the expected GPS interval when
 *   the app does not use SettingsRepository.
 * - allowedLatencyMs is implemented as an output smoothing time constant.
 */
data class ImsEkfConfig(
    val enabled: Boolean,
    val useCase: ImsUseCase,
    val gpsIntervalMsOverride: Long?,
    val allowedLatencyMs: Long,

    val imuSensorDelay: Int,
    val accelNoiseStdMps2: Double,
    val gpsAccuracyMultiplier: Double,
    val maxSpeedMps: Double,

    val staticAccelThresholdMps2: Double,
    val biasUpdateAlpha: Double
) {
    companion object {
        fun defaults(useCase: ImsUseCase): ImsEkfConfig {
            return when (useCase) {
                ImsUseCase.WALK ->
                    ImsEkfConfig(
                        enabled = false,
                        useCase = useCase,
                        gpsIntervalMsOverride = null,
                        allowedLatencyMs = 0L,
                        imuSensorDelay = android.hardware.SensorManager.SENSOR_DELAY_GAME,
                        accelNoiseStdMps2 = 1.8,
                        gpsAccuracyMultiplier = 1.0,
                        maxSpeedMps = 7.0,
                        staticAccelThresholdMps2 = 0.35,
                        biasUpdateAlpha = 0.02
                    )
                ImsUseCase.BIKE ->
                    ImsEkfConfig(
                        enabled = false,
                        useCase = useCase,
                        gpsIntervalMsOverride = null,
                        allowedLatencyMs = 0L,
                        imuSensorDelay = android.hardware.SensorManager.SENSOR_DELAY_GAME,
                        accelNoiseStdMps2 = 1.2,
                        gpsAccuracyMultiplier = 1.0,
                        maxSpeedMps = 25.0,
                        staticAccelThresholdMps2 = 0.45,
                        biasUpdateAlpha = 0.02
                    )
                ImsUseCase.CAR ->
                    ImsEkfConfig(
                        enabled = false,
                        useCase = useCase,
                        gpsIntervalMsOverride = null,
                        allowedLatencyMs = 0L,
                        imuSensorDelay = android.hardware.SensorManager.SENSOR_DELAY_GAME,
                        accelNoiseStdMps2 = 0.9,
                        gpsAccuracyMultiplier = 1.0,
                        maxSpeedMps = 70.0,
                        staticAccelThresholdMps2 = 0.6,
                        biasUpdateAlpha = 0.01
                    )
                ImsUseCase.TRAIN ->
                    ImsEkfConfig(
                        enabled = false,
                        useCase = useCase,
                        gpsIntervalMsOverride = null,
                        allowedLatencyMs = 0L,
                        imuSensorDelay = android.hardware.SensorManager.SENSOR_DELAY_GAME,
                        accelNoiseStdMps2 = 0.8,
                        gpsAccuracyMultiplier = 1.0,
                        maxSpeedMps = 120.0,
                        staticAccelThresholdMps2 = 0.6,
                        biasUpdateAlpha = 0.01
                    )
            }
        }

        fun clamp(config: ImsEkfConfig): ImsEkfConfig {
            return config.copy(
                allowedLatencyMs = max(0L, config.allowedLatencyMs),
                gpsIntervalMsOverride = config.gpsIntervalMsOverride?.let { max(250L, it) },
                accelNoiseStdMps2 = config.accelNoiseStdMps2.coerceAtLeast(0.1),
                gpsAccuracyMultiplier = config.gpsAccuracyMultiplier.coerceAtLeast(0.2),
                maxSpeedMps = config.maxSpeedMps.coerceAtLeast(0.1),
                staticAccelThresholdMps2 = config.staticAccelThresholdMps2.coerceAtLeast(0.05),
                biasUpdateAlpha = config.biasUpdateAlpha.coerceIn(0.0, 1.0)
            )
        }
    }
}

