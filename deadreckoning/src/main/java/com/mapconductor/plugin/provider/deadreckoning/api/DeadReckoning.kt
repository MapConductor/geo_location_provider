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

    suspend fun submitGpsFix(fix: GpsFix)

    /**
     * Return predicted points for [fromMillis, toMillis].
     *
     * Time step selection is delegated to the caller's scheduler
     * (for example driven by UI settings), and DeadReckoning returns
     * representative values based on its internal state.
     */
    suspend fun predict(fromMillis: Long, toMillis: Long): List<PredictedPoint>

    /** Whether required sensors (accelerometer/gyro) are available. */
    fun isImuCapable(): Boolean

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

