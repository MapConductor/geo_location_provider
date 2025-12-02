package com.mapconductor.plugin.provider.deadreckoning.engine

import android.hardware.SensorManager
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import kotlin.math.*

/**
 * Tier A Dead Reckoning engine.
 *
 * - Uses accelerometer / gyro / rotation-vector inputs to estimate position, speed, and heading.
 * - Combines ZUPT-based static detection and GPS fixes for correction.
 * - Tracks position uncertainty (sigma2Pos) alongside state.
 * - Applies simple motion mode switching (walking / vehicle) based on GPS speed
 *   so that tuning parameters can better match the current use case.
 *
 * Exposed to callers via the DeadReckoning interface; this class itself is kept internal.
 */
internal class DeadReckoningEngine(
    private val config: DeadReckoningConfig = DeadReckoningConfig()
) {

    private enum class MotionMode {
        Walking,
        Vehicle
    }

    private data class MotionParams(
        val forwardDeadzoneMps2: Float,
        val forwardMaxMps2: Float,
        val speedFrictionMps2: Float,
        val maxSpeedMps: Float,
        val posGain: Double,
        val velocityGain: Float
    )

    private val state = DrState()
    private val unc = DrUncertainty()
    private var hasGpsFix = false
    private var mode: MotionMode = MotionMode.Walking

    // Gravity estimation filter.
    private var gEst = floatArrayOf(0f, 0f, 9.81f)
    private val gAlpha = 0.90f

    // Thresholds for ZUPT-based static detection.
    private val aVarTh = config.staticAccelVarThreshold       // (m/s^2)^2
    private val wVarTh = config.staticGyroVarThreshold        // (rad/s)^2

    // Position process noise.
    private val qPos = config.processNoisePos                 // m^2/s

    // Windows for static detection.
    private val aWin = CircularWindow(config.windowSize)
    private val wWin = CircularWindow(config.windowSize)

    // Maximum physically plausible speed for a single integration step.
    private val maxStepSpeedMps: Float =
        if (config.maxStepSpeedMps <= 0f) 0f else config.maxStepSpeedMps

    // When the device is rotated or accelerated very quickly while DR believes the
    // user is almost stopped, treat it as hand motion and skip position integration
    // for that sample.
    private val rotationRejectThresholdRadPerSec: Float = 2.5f
    private val accelSpikeThresholdMps2: Float = 6.0f

    // Motion model parameters are switched dynamically by motion mode so that
    // walking and vehicle use cases can be tuned independently while sharing
    // the same engine implementation.
    private val walkingParams = MotionParams(
        forwardDeadzoneMps2 = 0.05f,
        forwardMaxMps2 = 3.0f,
        // Make friction and GPS position blending slightly stronger so that
        // DR keeps up with walking speed without trailing far behind GPS.
        speedFrictionMps2 = 0.06f,
        maxSpeedMps = 4.5f, // Slightly above typical walking / jogging.
        posGain = 0.2,
        velocityGain = config.velocityGain
    )

    private val vehicleParams = MotionParams(
        forwardDeadzoneMps2 = 0.05f,
        forwardMaxMps2 = 6.0f,
        speedFrictionMps2 = 0.02f,
        maxSpeedMps = 60.0f, // Allow typical train / car speeds.
        posGain = 0.3,
        velocityGain = max(config.velocityGain, 0.3f)
    )

    private val motionParams: MotionParams
        get() = when (mode) {
            MotionMode.Walking -> walkingParams
            MotionMode.Vehicle -> vehicleParams
        }

    // Motion mode switching is driven by GPS speed with a simple hysteresis
    // in the time domain so that intermittent speed spikes do not cause
    // rapid flipping between modes.
    private val vehicleEnterSpeedMps: Float = 15.0f   // About 54 km/h.
    private val walkingEnterSpeedMps: Float = 8.0f    // About 29 km/h.
    private val vehicleEnterDurationMillis: Long = 5_000L
    private val walkingEnterDurationMillis: Long = 10_000L

    private var lastFastSeenMillis: Long? = null
    private var lastSlowSeenMillis: Long? = null

    // Optional debug logging switch.
    private val debugLogging: Boolean = config.debugLogging

    // Cached rotation matrix/orientation derived from rotation vector.
    private val rotMat = FloatArray(9)
    private val rotOrientation = FloatArray(3)
    private var hasRotMat: Boolean = false

    fun hasGpsFix(): Boolean = hasGpsFix

    fun isLikelyStatic(): Boolean {
        val aVar = aWin.variance()
        val wVar = wWin.variance()
        return aVar != null && wVar != null && aVar < aVarTh && wVar < wVarTh
    }

    fun onSensor(
        acc: FloatArray?,
        gyro: FloatArray?,
        mag: FloatArray?,
        rotVec: FloatArray?,
        dtSec: Float
    ) {
        // Update statistics for static detection.
        acc?.let { aWin.push(norm2(it)) }
        gyro?.let { wWin.push(norm2(it)) }

        // Update heading. Prefer rotation vector when available, fall back to
        // simple magnetometer-based yaw.
        val headingFromRot: Float? = if (rotVec != null) {
            try {
                SensorManager.getRotationMatrixFromVector(rotMat, rotVec)
                SensorManager.getOrientation(rotMat, rotOrientation)
                hasRotMat = true
                rotOrientation[0] // azimuth [rad], -pi..pi
            } catch (t: Throwable) {
                hasRotMat = false
                null
            }
        } else {
            hasRotMat = false
            null
        }

        val heading: Float = when {
            headingFromRot != null -> headingFromRot
            mag != null -> atan2(mag[0].toDouble(), mag[1].toDouble()).toFloat()
            else -> state.headingRad
        }
        state.headingRad = wrapAngle(lerpAngle(state.headingRad, heading, 0.05f))

        // Subtract gravity and update speed/position based on horizontal acceleration.
        if (acc != null) {
            for (i in 0..2) gEst[i] = gAlpha * gEst[i] + (1 - gAlpha) * acc[i]
            val lin = floatArrayOf(acc[0] - gEst[0], acc[1] - gEst[1], acc[2] - gEst[2])

            // If the device is being rotated or accelerated very quickly while
            // DR believes we are almost stopped, this is likely hand/arm motion
            // rather than real translation. In that case, skip this integration
            // step but keep statistics up to date.
            val gyroNorm = gyro?.let { norm2(it) } ?: 0f
            val linNorm = norm2(lin)
            val strongRotation = gyroNorm > rotationRejectThresholdRadPerSec
            val strongAccel = linNorm > accelSpikeThresholdMps2
            val staticNow = isLikelyStatic()
            if (staticNow && (strongRotation || strongAccel)) {
                if (debugLogging) {
                    Log.d(
                        "DR/HAND",
                        "suppress integration gyro=${gyroNorm}rad/s accel=${linNorm}m/s^2 speed=${state.speedMps}m/s"
                    )
                }
                return
            }

            val aH = if (hasRotMat) {
                // Transform linear acceleration into navigation frame using the
                // rotation matrix and then take horizontal components (east/north).
                val ax = rotMat[0] * lin[0] + rotMat[1] * lin[1] + rotMat[2] * lin[2]
                val ay = rotMat[3] * lin[0] + rotMat[4] * lin[1] + rotMat[5] * lin[2]
                floatArrayOf(ax, ay)
            } else {
                // Fallback: project using current heading only.
                horizontalProjection(lin, state.headingRad)
            }

            // Integrate only the component of horizontal acceleration that lies
            // along the current heading direction. This reduces sensitivity to
            // sideways hand motion and keeps acceleration effects more physical.
            val params = motionParams
            val cHead = cos(state.headingRad)
            val sHead = sin(state.headingRad)
            var aForward = (aH[0] * cHead + aH[1] * sHead).toFloat()

            // Apply a small deadzone so that tiny accelerations do not build up
            // speed. This suppresses sensor noise and small jitters.
            if (aForward > -params.forwardDeadzoneMps2 && aForward < params.forwardDeadzoneMps2) {
                aForward = 0f
            } else if (aForward > 0f) {
                aForward -= params.forwardDeadzoneMps2
            } else {
                aForward += params.forwardDeadzoneMps2
            }

            // Clamp forward acceleration to a reasonable range.
            aForward = aForward.coerceIn(-params.forwardMaxMps2, params.forwardMaxMps2)

            // Predict speed with a simple friction term so that the effect of
            // short impulses decays over time instead of accumulating.
            var newSpeed = state.speedMps + (aForward * dtSec)
            if (newSpeed > 0f) {
                newSpeed -= params.speedFrictionMps2 * dtSec
            }
            if (newSpeed < 0f) newSpeed = 0f
            if (newSpeed > params.maxSpeedMps) newSpeed = params.maxSpeedMps

            // Predict displacement for this step using the updated speed.
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

            // Always integrate displacement based on the current speed so that
            // the engine can move away from the last GPS fix during walking.
            // Static use cases are handled by higher-level clamping if needed.
            applyDisplacementMeters(dx.toDouble(), dy.toDouble())
            unc.sigma2Pos += qPos * dtSec
        }
    }

    fun submitGpsFix(timestampMillis: Long, lat: Double, lon: Double, accM: Float?, speedMps: Float?) {
        val r = max(accM ?: 10f, 5f)
        val now = timestampMillis

        // Update motion mode state from GPS speed.
        updateMotionMode(now, speedMps)

        // If we already have a GPS anchor and the device is moving,
        // use the course between the previous and current fix to gently
        // steer the internal heading toward the true travel direction.
        if (hasGpsFix && speedMps != null && speedMps > 0.5f) {
            val courseRad = bearingRad(state.lat, state.lon, lat, lon)
            val blend = 0.2f
            state.headingRad = wrapAngle(lerpAngle(state.headingRad, courseRad, blend))
        }

        // Position correction policy:
        // - On the first GPS fix, hard-anchor the internal position.
        // - On subsequent fixes, blend toward GPS based on distance and
        //   reported accuracy so that DR follows GPS but is not dragged
        //   excessively by noisy fixes.
        if (!hasGpsFix) {
            // No previous anchor: use GPS as-is.
            state.lat = lat
            state.lon = lon
        } else {
            // Distance-based blend so that small corrections stay smooth while
            // large jumps converge more quickly.
            val dist = distanceMeters(state.lat, state.lon, lat, lon)
            val base = motionParams.posGain
            val distGain =
                when {
                    dist <= 30.0 -> base
                    dist <= 150.0 -> max(base, 0.5)
                    else -> 1.0
                }
            // When reported accuracy is poor, scale down the influence of
            // this GPS fix. Use a simple 10m / accuracy rule with a lower
            // bound so that extremely low gains are avoided.
            val accuracyScale =
                if (accM != null && !accM.isNaN() && accM > 0f) {
                    val s = 10.0 / accM.toDouble()
                    s.coerceIn(0.3, 1.0)
                } else {
                    1.0
                }
            val posGain = (distGain * accuracyScale).coerceIn(0.0, 1.0)
            state.lat += posGain * (lat - state.lat)
            state.lon += posGain * (lon - state.lon)
        }

        // Blend speed toward GPS speed when available.
        if (speedMps != null) {
            val kV =
                when (mode) {
                    MotionMode.Walking -> motionParams.velocityGain
                    MotionMode.Vehicle -> motionParams.velocityGain
                }
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

    private fun norm2(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun horizontalProjection(a: FloatArray, headingRad: Float): FloatArray {
        val c = cos(headingRad); val s = sin(headingRad)
        val ax = a[0] * c + a[1] * (-s)
        val ay = a[0] * s + a[1] * c
        return floatArrayOf(ax, ay)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * asin(sqrt(a))
        return r * c
    }

    private fun updateMotionMode(nowMillis: Long, speedMps: Float?) {
        if (speedMps != null && !speedMps.isNaN()) {
            if (speedMps >= vehicleEnterSpeedMps) {
                lastFastSeenMillis = nowMillis
            } else if (speedMps <= walkingEnterSpeedMps) {
                lastSlowSeenMillis = nowMillis
            }
        }

        when (mode) {
            MotionMode.Walking -> {
                val fastAt = lastFastSeenMillis
                if (fastAt != null && nowMillis - fastAt >= vehicleEnterDurationMillis) {
                    mode = MotionMode.Vehicle
                    if (debugLogging) {
                        Log.d("DR/MODE", "switch mode=Vehicle speed=$speedMps")
                    }
                }
            }

            MotionMode.Vehicle -> {
                val slowAt = lastSlowSeenMillis
                if (slowAt != null && nowMillis - slowAt >= walkingEnterDurationMillis) {
                    mode = MotionMode.Walking
                    if (debugLogging) {
                        Log.d("DR/MODE", "switch mode=Walking speed=$speedMps")
                    }
                }
            }
        }
    }

    private fun bearingRad(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return atan2(y, x).toFloat()
    }

    private fun wrapAngle(rad: Float): Float {
        var r = rad
        while (r <= -Math.PI) r += (2 * Math.PI).toFloat()
        while (r > Math.PI) r -= (2 * Math.PI).toFloat()
        return r
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        return a + d * t
    }

    /** Simple circular window for sensor statistics. */
    private class CircularWindow(cap: Int) {
        private val buf = FloatArray(cap)
        private var idx = 0
        private var filled = 0
        fun push(v: Float) {
            buf[idx] = v; idx = (idx + 1) % buf.size; if (filled < buf.size) filled++
        }
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
