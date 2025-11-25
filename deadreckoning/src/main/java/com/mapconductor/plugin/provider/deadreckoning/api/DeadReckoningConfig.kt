package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

/**
 * Configuration values that control DeadReckoning behavior.
 *
 * Role:
 * - Provide tuning parameters for static detection and position noise model so that
 *   library users can adjust behavior as needed.
 *
 * Default values are tuned for the sample app and are generally fine for normal use.
 */
data class DeadReckoningConfig(
    /** Threshold of accelerometer variance for static detection (approx m^2/s^4). */
    val staticAccelVarThreshold: Float = 0.02f,
    /** Threshold of gyro variance for static detection ((rad/s)^2). */
    val staticGyroVarThreshold: Float = 0.005f * 0.005f,
    /** Process noise of position (m^2/s). */
    val processNoisePos: Float = 4f,
    /** Blend factor between GPS speed and internal speed estimate. */
    val velocityGain: Float = 0.1f,
    /** Moving window size used for static detection. */
    val windowSize: Int = 64
)

