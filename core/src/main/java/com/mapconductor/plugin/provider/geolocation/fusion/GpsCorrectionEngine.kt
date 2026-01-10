package com.mapconductor.plugin.provider.geolocation.fusion

import com.mapconductor.plugin.provider.geolocation.gps.GpsObservation

/**
 * Applies correction to raw GPS observations.
 *
 * This is the extension point for inertial-aided correction before the
 * observation is used for persistence and as a DeadReckoning anchor.
 */
interface GpsCorrectionEngine {
    fun correct(observation: GpsObservation, hint: GpsCorrectionHint): GpsObservation
}

data class GpsCorrectionHint(
    val headingTrueDeg: Float?,
    val speedMps: Float,
    val isLikelyStatic: Boolean,
    val updateIntervalMs: Long
)

object NoopGpsCorrectionEngine : GpsCorrectionEngine {
    override fun correct(observation: GpsObservation, hint: GpsCorrectionHint): GpsObservation {
        return observation
    }
}

/**
 * Optional lifecycle hooks for correction engines that use sensors.
 *
 * GeoLocationService starts/stops this together with location tracking to avoid
 * unnecessary IMU sampling.
 */
interface LifecycleAwareGpsCorrectionEngine : GpsCorrectionEngine {
    fun start()
    fun stop()
}

