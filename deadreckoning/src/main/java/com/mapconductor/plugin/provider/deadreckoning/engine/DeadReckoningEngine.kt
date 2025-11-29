package com.mapconductor.plugin.provider.deadreckoning.engine

import android.util.Log
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import kotlin.math.*

/**
 * Tier A Dead Reckoning engine.
 *
 * - Uses accelerometer / gyro / magnetometer inputs to estimate position, speed, and heading.
 * - Combines ZUPT-based static detection and GPS fixes for correction.
 * - Tracks position uncertainty (sigma2Pos) alongside state.
 *
 * Exposed to callers via the DeadReckoning interface; this class itself is kept internal.
 */
internal class DeadReckoningEngine(
    private val config: DeadReckoningConfig = DeadReckoningConfig()
) {

    private val state = DrState()
    private val unc = DrUncertainty()
    private var hasGpsFix = false

    // Gravity estimation filter.
    private var gEst = floatArrayOf(0f, 0f, 9.81f)
    private val gAlpha = 0.90f

    // Thresholds for ZUPT-based static detection.
    private val aVarTh = config.staticAccelVarThreshold       // (m/s^2)^2
    private val wVarTh = config.staticGyroVarThreshold        // (rad/s)^2

    // Position process noise.
    private val qPos = config.processNoisePos                 // m^2/s

    // Blend factor with GPS speed.
    private val kV = config.velocityGain

    // Windows for static detection.
    private val aWin = CircularWindow(config.windowSize)
    private val wWin = CircularWindow(config.windowSize)

    // Maximum physically plausible speed for a single integration step.
    private val maxStepSpeedMps: Float =
        if (config.maxStepSpeedMps <= 0f) 0f else config.maxStepSpeedMps

    // Optional debug logging switch.
    private val debugLogging: Boolean = config.debugLogging

    fun hasGpsFix(): Boolean = hasGpsFix

    fun isLikelyStatic(): Boolean {
        val aVar = aWin.variance()
        val wVar = wWin.variance()
        return aVar != null && wVar != null && aVar < aVarTh && wVar < wVarTh
    }

    fun onSensor(acc: FloatArray?, gyro: FloatArray?, mag: FloatArray?, dtSec: Float) {
        // Update statistics for static detection.
        acc?.let { aWin.push(norm2(it)) }
        gyro?.let { wWin.push(norm2(it)) }

        // Update heading (simple yaw from magnetometer when available).
        val heading = if (mag != null) atan2(mag[0].toDouble(), mag[1].toDouble()).toFloat() else state.headingRad
        state.headingRad = wrapAngle(lerpAngle(state.headingRad, heading, 0.05f))

        // Subtract gravity and update speed/position based on horizontal acceleration.
        if (acc != null) {
            for (i in 0..2) gEst[i] = gAlpha * gEst[i] + (1 - gAlpha) * acc[i]
            val lin = floatArrayOf(acc[0] - gEst[0], acc[1] - gEst[1], acc[2] - gEst[2])

            val aH = horizontalProjection(lin, state.headingRad) // nav frame (x:east, y:north)
            val aMag = hypot(aH[0], aH[1])

            // Predict speed and displacement for this step.
            val newSpeed = max(0f, state.speedMps + (aMag * dtSec))
            val dx = newSpeed * dtSec * cos(state.headingRad)
            val dy = newSpeed * dtSec * sin(state.headingRad)

            // Simple physical sanity check: reject steps that would imply
            // unrealistically high speed. This protects against sporadic
            // IMU spikes without involving the service layer.
            val dist = hypot(dx, dy)
            val stepSpeedMps: Float =
                if (dtSec > 0f) (dist / dtSec.toDouble()).toFloat() else 0f
            if (maxStepSpeedMps > 0f && stepSpeedMps > maxStepSpeedMps) {
                if (debugLogging) {
                    Log.d(
                        "DR/SANITY",
                        "reject step speed=${stepSpeedMps}m/s limit=${maxStepSpeedMps}m/s dist=${dist}m dt=${dtSec}s"
                    )
                }
                // Ignore this step; keep previous state but still update statistics.
                return
            }

            state.speedMps = newSpeed

            // When considered static, gradually decay speed.
            if (isLikelyStatic()) state.speedMps *= 0.85f

            applyDisplacementMeters(dx.toDouble(), dy.toDouble())

            // Increase position uncertainty over time.
            unc.sigma2Pos += qPos * dtSec
        }
    }

    fun submitGpsFix(lat: Double, lon: Double, accM: Float?, speedMps: Float?) {
        val r = max(accM ?: 10f, 5f)

        // Always re-anchor position to the latest GPS fix so that
        // DR integrates from the most recent GPS and its subsequent history.
        state.lat = lat
        state.lon = lon

        // Blend speed toward GPS speed when available.
        if (speedMps != null) {
            state.speedMps = state.speedMps + kV * (speedMps - state.speedMps)
        }

        // Reset position uncertainty based on the current GPS accuracy.
        unc.sigma2Pos = r * r
        hasGpsFix = true
    }

    fun snapshot(): Pair<DrState, DrUncertainty> = state.copy() to unc.copy()

    private fun applyDisplacementMeters(dxEast: Double, dyNorth: Double) {
        val latRad = Math.toRadians(state.lat)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(latRad)
        state.lat += (dyNorth / metersPerDegLat)
        state.lon += (dxEast / metersPerDegLon)
    }

    private fun norm2(v: FloatArray): Float = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])

    private fun horizontalProjection(a: FloatArray, headingRad: Float): FloatArray {
        val c = cos(headingRad); val s = sin(headingRad)
        val ax = a[0]*c + a[1]*(-s)
        val ay = a[0]*s + a[1]*c
        return floatArrayOf(ax, ay)
    }

    private fun wrapAngle(rad: Float): Float {
        var r = rad
        while (r <= -Math.PI) r += (2*Math.PI).toFloat()
        while (r > Math.PI) r -= (2*Math.PI).toFloat()
        return r
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        while (d < -Math.PI) d += (2*Math.PI).toFloat()
        while (d > Math.PI) d -= (2*Math.PI).toFloat()
        return a + d * t
    }

    /** Simple circular window for sensor statistics. */
    private class CircularWindow(cap: Int) {
        private val buf = FloatArray(cap)
        private var idx = 0
        private var filled = 0
        fun push(v: Float) { buf[idx] = v; idx = (idx + 1) % buf.size; if (filled < buf.size) filled++ }
        fun variance(): Float? {
            if (filled < 8) return null
            var sum = 0f
            for (i in 0 until filled) sum += buf[i]
            val mean = sum / filled
            var s2 = 0f
            for (i in 0 until filled) {
                val d = buf[i] - mean
                s2 += d * d
            }
            return s2 / (filled - 1)
        }
    }
}
