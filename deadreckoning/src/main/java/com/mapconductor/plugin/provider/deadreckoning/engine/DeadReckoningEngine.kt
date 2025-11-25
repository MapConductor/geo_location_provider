package com.mapconductor.plugin.provider.deadreckoning.engine

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
            state.speedMps = max(0f, state.speedMps + (hypot(aH[0], aH[1]) * dtSec))

            // When considered static, gradually decay speed.
            if (isLikelyStatic()) state.speedMps *= 0.85f

            val dx = state.speedMps * dtSec * cos(state.headingRad)
            val dy = state.speedMps * dtSec * sin(state.headingRad)
            applyDisplacementMeters(dx.toDouble(), dy.toDouble())

            // Increase position uncertainty over time.
            unc.sigma2Pos += qPos * dtSec
        }
    }

    fun submitGpsFix(lat: Double, lon: Double, accM: Float?, speedMps: Float?) {
        val r = max(accM ?: 10f, 5f)
        val K = unc.sigma2Pos / (unc.sigma2Pos + r * r)

        // Correct position.
        state.lat = state.lat + K * (lat - state.lat)
        state.lon = state.lon + K * (lon - state.lon)

        // Correct speed.
        if (speedMps != null) {
            state.speedMps = state.speedMps + kV * (speedMps - state.speedMps)
        }

        // Update uncertainty.
        unc.sigma2Pos = (1 - K) * unc.sigma2Pos
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

