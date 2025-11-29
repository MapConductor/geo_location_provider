package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

import java.io.Closeable

/**
 * Public interface of DeadReckoning.
 *
 * - start()/stop() control sensor subscription.
 * - GPS fixes are passed via submitGpsFix() to correct drift.
 * - predict() returns predicted points for a given time range.
 */
interface DeadReckoning : Closeable {
    fun start()
    fun stop()

    /**
     * Provide a GPS fix to the engine.
     *
     * Implementations are expected to re-anchor their internal
     * position state to this fix so that subsequent DR integration
     * always starts from the most recent GPS location.
     */
    suspend fun submitGpsFix(fix: GpsFix)

    /**
     * Return predicted points for [fromMillis, toMillis].
     *
     * Time step selection is delegated to the caller's scheduler
     * (for example driven by UI settings), and DeadReckoning returns
     * representative values based on its internal state.
     *
     * Before the first GPS fix is submitted, implementations may
     * return an empty list because the absolute position is not
     * initialized yet.
     */
    suspend fun predict(fromMillis: Long, toMillis: Long): List<PredictedPoint>

    /** Whether required sensors (accelerometer/gyro) are available. */
    fun isImuCapable(): Boolean

    /**
     * Whether the engine currently considers the device to be almost static.
     *
     * This is based on short term variance of accelerometer and gyro inputs
     * (ZUPT-like heuristic) and can be used by callers to decide when to
     * clamp DR updates near the latest known good location.
     */
    fun isLikelyStatic(): Boolean

    override fun close() = stop()
}

data class GpsFix(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedMps: Float?
)

data class PredictedPoint(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,      // Approximation of estimation error.
    val speedMps: Float?,       // Estimated speed.
    val horizontalStdM: Float?  // Isotropic 1-sigma (horizontal) for Tier A.
)
