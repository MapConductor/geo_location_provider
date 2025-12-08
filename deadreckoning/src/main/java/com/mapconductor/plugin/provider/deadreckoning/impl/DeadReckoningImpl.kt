package com.mapconductor.plugin.provider.deadreckoning.impl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoning
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.GpsFix
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.PredictedPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * DeadReckoning implementation with a straight-line 1D model.
 *
 * Behavior:
 * - Uses GPS fixes as hard anchors for absolute position.
 * - Integrates a 1D motion state along the latest GPS direction so that
 *   the DR position always stays on that straight line.
 * - Static / moving state is decided inside this class based on GPS
 *   speed with hysteresis and exposed via isLikelyStatic().
 * - Accelerometer input is used to drive speed changes toward the latest
 *   GPS speed while keeping the straight-line constraint.
 */
internal class DeadReckoningImpl(
    private val appContext: Context,
    private val config: DeadReckoningConfig
) : DeadReckoning {

    companion object {
        // Speed thresholds for hysteresis.
        private const val ENTER_STATIC_SPEED_MPS = 0.8f
        private const val EXIT_STATIC_SPEED_MPS = 1.0f

        // Horizontal acceleration thresholds (mean over window) used for
        // scaling motionFactor in the IMU-driven speed relaxation.
        private const val ACC_STATIC_THRESHOLD = 0.3f
        private const val ACC_MOVING_THRESHOLD = 0.8f

        // Duration of the horizontal acceleration window [ms].
        private const val ACC_WINDOW_MILLIS = 1000L

        // Approximate earth radius in meters.
        private const val EARTH_RADIUS_M = 6371000.0

        // Relaxation time constant for speed convergence [s].
        private const val SPEED_RELAXATION_TIME_SEC = 1.0

        // Maximum allowed integration gap between accelerometer samples [s].
        private const val MAX_INTEGRATION_GAP_SEC = 5.0
    }

    @Volatile
    private var lastFix: GpsFix? = null

    // Previous and latest GPS hold values.
    @Volatile
    private var prevHoldLat: Double? = null
    @Volatile
    private var prevHoldLon: Double? = null
    @Volatile
    private var prevHoldTimeMillis: Long? = null

    @Volatile
    private var lastHoldLat: Double? = null
    @Volatile
    private var lastHoldLon: Double? = null
    @Volatile
    private var lastHoldTimeMillis: Long? = null

    @Volatile
    private var isStaticFlag: Boolean = false

    // 1D DR state along the latest GPS direction.
    private val drLock = Any()
    @Volatile
    private var drAnchorLat: Double? = null
    @Volatile
    private var drAnchorLon: Double? = null
    @Volatile
    private var drAnchorTimeMillis: Long? = null
    @Volatile
    private var drDirEastUnit: Double? = null
    @Volatile
    private var drDirNorthUnit: Double? = null
    @Volatile
    private var drBaselineSpeedMps: Double? = null
    @Volatile
    private var drSpeedMps: Double = 0.0
    @Volatile
    private var drOffsetMeters: Double = 0.0
    @Volatile
    private var drLastUpdateTimeMillis: Long? = null

    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    @Volatile
    private var sensorListener: SensorEventListener? = null

    private val accelLock = Any()
    private val accelWindow = HorizontalAccelWindow(ACC_WINDOW_MILLIS)

    override fun start() {
        val mgr = sensorManager ?: return
        if (sensorListener != null) {
            return
        }
        val accSensor = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
                    return
                }
                if (event.values.size < 2) {
                    return
                }
                val ax = event.values[0]
                val ay = event.values[1]
                val horizontal = sqrt(ax * ax + ay * ay)
                val now = System.currentTimeMillis()
                synchronized(accelLock) {
                    accelWindow.push(now, horizontal)
                }
                onAccelerometerSample(now, horizontal.toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op.
            }
        }
        sensorListener = listener
        mgr.registerListener(listener, accSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        val mgr = sensorManager
        val listener = sensorListener
        if (mgr != null && listener != null) {
            mgr.unregisterListener(listener)
        }
        sensorListener = null
        synchronized(accelLock) {
            accelWindow.clear()
        }
    }

    override suspend fun submitGpsFix(fix: GpsFix) {
        lastFix = fix

        // Update GPS hold values.
        val lat = fix.lat
        val lon = fix.lon
        val timeMillis = fix.timestampMillis

        val prevLat = lastHoldLat
        val prevLon = lastHoldLon
        val prevTime = lastHoldTimeMillis

        if (prevLat == null || prevLon == null || prevTime == null) {
            // First hold value: only lastHold is available, prevHold stays null.
            lastHoldLat = lat
            lastHoldLon = lon
            lastHoldTimeMillis = timeMillis
            prevHoldLat = null
            prevHoldLon = null
            prevHoldTimeMillis = null
        } else {
            // Shift last hold to previous and store the new one as latest.
            prevHoldLat = prevLat
            prevHoldLon = prevLon
            prevHoldTimeMillis = prevTime

            lastHoldLat = lat
            lastHoldLon = lon
            lastHoldTimeMillis = timeMillis
        }

        // Re-anchor the 1D DR state along the latest GPS direction.
        synchronized(drLock) {
            val hasPrev = prevLat != null && prevLon != null && prevTime != null && timeMillis > (prevTime ?: 0L)
            val baselineSpeed = selectBaselineSpeedMps(fix, prevLat, prevLon, prevTime)

            drAnchorLat = lat
            drAnchorLon = lon
            drAnchorTimeMillis = timeMillis
            drBaselineSpeedMps = baselineSpeed
            drSpeedMps = baselineSpeed
            drOffsetMeters = 0.0
            drLastUpdateTimeMillis = timeMillis

            if (hasPrev && prevLat != null && prevLon != null) {
                val dir = computeDirectionUnit(prevLat, prevLon, lat, lon)
                if (dir != null) {
                    drDirEastUnit = dir.first
                    drDirNorthUnit = dir.second
                }
            }
        }

        updateStaticState(fix)
    }

    override suspend fun predict(fromMillis: Long, toMillis: Long): List<PredictedPoint> {
        val fix = lastFix ?: return emptyList()

        // Before the first GPS hold value is available, there is no absolute
        // position to return.
        val holdLat = lastHoldLat
        val holdLon = lastHoldLon
        val holdTime = lastHoldTimeMillis
        if (holdLat == null || holdLon == null || holdTime == null) {
            return emptyList()
        }

        // Take a snapshot of the current DR state to avoid holding the lock
        // while performing trigonometric operations.
        val snapshot = synchronized(drLock) {
            DrSnapshot(
                anchorLat = drAnchorLat ?: holdLat,
                anchorLon = drAnchorLon ?: holdLon,
                anchorTimeMillis = drAnchorTimeMillis ?: holdTime,
                dirEastUnit = drDirEastUnit,
                dirNorthUnit = drDirNorthUnit,
                baselineSpeedMps = drBaselineSpeedMps ?: 0.0,
                speedMps = drSpeedMps,
                offsetMeters = drOffsetMeters,
                lastUpdateTimeMillis = drLastUpdateTimeMillis ?: holdTime
            )
        }

        var drLat = holdLat
        var drLon = holdLon
        var drSpeed = snapshot.speedMps

        val dirEast = snapshot.dirEastUnit
        val dirNorth = snapshot.dirNorthUnit

        if (dirEast != null && dirNorth != null) {
            val dtSec = (toMillis - snapshot.lastUpdateTimeMillis).toDouble() / 1000.0
            val offsetAtTo = if (dtSec > 0.0) {
                snapshot.offsetMeters + snapshot.speedMps * dtSec
            } else {
                snapshot.offsetMeters
            }

            val pos = positionAlongDirection(
                anchorLat = snapshot.anchorLat,
                anchorLon = snapshot.anchorLon,
                dirEastUnit = dirEast,
                dirNorthUnit = dirNorth,
                offsetMeters = offsetAtTo
            )
            drLat = pos.first
            drLon = pos.second

            drSpeed = snapshot.speedMps
        }

        // When static is detected, keep DR position pinned to the latest
        // GPS hold location regardless of the integrated value.
        if (isStaticFlag) {
            drLat = holdLat
            drLon = holdLon
            drSpeed = 0.0
        }

        val acc = fix.accuracyM?.takeIf { it > 0f && !it.isNaN() }
        val speed = drSpeed.toFloat().takeIf { it > 0f } ?: fix.speedMps?.takeIf { !it.isNaN() }

        return listOf(
            PredictedPoint(
                timestampMillis = toMillis,
                lat = drLat,
                lon = drLon,
                accuracyM = acc,
                speedMps = speed,
                horizontalStdM = acc
            )
        )
    }

    override fun isImuCapable(): Boolean {
        val mgr = sensorManager ?: return false
        val acc = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        return acc != null
    }

    override fun isLikelyStatic(): Boolean {
        return isStaticFlag
    }

    private fun onAccelerometerSample(timeMillis: Long, horizontalAccel: Double) {
        synchronized(drLock) {
            val anchorLat = drAnchorLat
            val anchorLon = drAnchorLon
            val dirEast = drDirEastUnit
            val dirNorth = drDirNorthUnit
            val baselineSpeed = drBaselineSpeedMps
            val lastTime = drLastUpdateTimeMillis

            if (anchorLat == null || anchorLon == null || dirEast == null || dirNorth == null) {
                drLastUpdateTimeMillis = timeMillis
                return
            }
            if (baselineSpeed == null) {
                drLastUpdateTimeMillis = timeMillis
                return
            }
            if (lastTime == null) {
                drLastUpdateTimeMillis = timeMillis
                return
            }

            val dtSec = (timeMillis - lastTime).toDouble() / 1000.0
            if (dtSec <= 0.0 || dtSec > MAX_INTEGRATION_GAP_SEC) {
                drLastUpdateTimeMillis = timeMillis
                return
            }

            if (isStaticFlag) {
                drSpeedMps = 0.0
                drOffsetMeters = 0.0
                drLastUpdateTimeMillis = timeMillis
                return
            }

            val motionFactor = computeMotionFactor(horizontalAccel)
            if (motionFactor <= 0.0) {
                drLastUpdateTimeMillis = timeMillis
                return
            }

            val speedTarget = baselineSpeed
            val currentSpeed = drSpeedMps
            val speedError = speedTarget - currentSpeed

            val relaxation = if (SPEED_RELAXATION_TIME_SEC > 0.0) {
                SPEED_RELAXATION_TIME_SEC
            } else {
                1.0
            }

            val accelParallel = motionFactor * (speedError / relaxation)
            var newSpeed = currentSpeed + accelParallel * dtSec

            if (newSpeed < 0.0) {
                newSpeed = 0.0
            }

            val maxSpeed = config.maxStepSpeedMps.toDouble()
            if (maxSpeed > 0.0 && newSpeed > maxSpeed) {
                newSpeed = maxSpeed
            }

            val meanSpeed = 0.5 * (currentSpeed + newSpeed)
            val newOffset = drOffsetMeters + meanSpeed * dtSec

            drSpeedMps = newSpeed
            drOffsetMeters = newOffset
            drLastUpdateTimeMillis = timeMillis
        }
    }

    private fun updateStaticState(fix: GpsFix) {
        // Static / moving classification is based on GPS speed only so
        // that hand motions on a mostly stationary user do not flip the
        // state. Accelerometer is still used in onAccelerometerSample()
        // to adjust DR speed while in the moving state.
        val speed = fix.speedMps?.takeIf { !it.isNaN() && it >= 0f } ?: 0f

        val staticCandidate = speed <= ENTER_STATIC_SPEED_MPS
        val movingCandidate = speed >= EXIT_STATIC_SPEED_MPS

        if (!isStaticFlag && staticCandidate) {
            isStaticFlag = true
        } else if (isStaticFlag && movingCandidate) {
            isStaticFlag = false
        }
    }

    private fun computeMotionFactor(horizontalAccel: Double): Double {
        val low = ACC_STATIC_THRESHOLD.toDouble()
        val high = ACC_MOVING_THRESHOLD.toDouble()
        if (horizontalAccel <= low) {
            return 0.0
        }
        if (horizontalAccel >= high) {
            return 1.0
        }
        return (horizontalAccel - low) / (high - low)
    }

    private fun selectBaselineSpeedMps(
        fix: GpsFix,
        prevLat: Double?,
        prevLon: Double?,
        prevTimeMillis: Long?
    ): Double {
        val gpsSpeed = fix.speedMps?.takeIf { !it.isNaN() && it > 0f }?.toDouble()
        if (gpsSpeed != null) {
            return gpsSpeed
        }

        if (prevLat != null && prevLon != null && prevTimeMillis != null && fix.timestampMillis > prevTimeMillis) {
            val distanceMeters = distanceMeters(prevLat, prevLon, fix.lat, fix.lon)
            val dtSec = (fix.timestampMillis - prevTimeMillis).toDouble() / 1000.0
            if (dtSec > 0.0) {
                val v = distanceMeters / dtSec
                if (v.isFinite() && v > 0.0) {
                    return v
                }
            }
        }

        return 0.0
    }

    private fun computeDirectionUnit(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Pair<Double, Double>? {
        val fromLatRad = Math.toRadians(fromLat)
        val toLatRad = Math.toRadians(toLat)
        val dLatRad = Math.toRadians(toLat - fromLat)
        val dLonRad = Math.toRadians(toLon - fromLon)

        val meanLatRad = 0.5 * (fromLatRad + toLatRad)

        val north = dLatRad * EARTH_RADIUS_M
        val east = dLonRad * cos(meanLatRad) * EARTH_RADIUS_M

        val norm = hypot(east, north)
        if (norm < 1e-3) {
            return null
        }
        return Pair(east / norm, north / norm)
    }

    private fun positionAlongDirection(
        anchorLat: Double,
        anchorLon: Double,
        dirEastUnit: Double,
        dirNorthUnit: Double,
        offsetMeters: Double
    ): Pair<Double, Double> {
        val latRad = Math.toRadians(anchorLat)
        val lonRad = Math.toRadians(anchorLon)

        val north = dirNorthUnit * offsetMeters
        val east = dirEastUnit * offsetMeters

        val newLatRad = latRad + (north / EARTH_RADIUS_M)
        val newLonRad = lonRad + (east / (EARTH_RADIUS_M * cos(latRad)))

        val newLatDeg = Math.toDegrees(newLatRad)
        val newLonDeg = Math.toDegrees(newLonRad)
        return Pair(newLatDeg, newLonDeg)
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Double {
        val fromLatRad = Math.toRadians(fromLat)
        val toLatRad = Math.toRadians(toLat)
        val dLatRad = Math.toRadians(toLat - fromLat)
        val dLonRad = Math.toRadians(toLon - fromLon)

        val meanLatRad = 0.5 * (fromLatRad + toLatRad)

        val north = dLatRad * EARTH_RADIUS_M
        val east = dLonRad * cos(meanLatRad) * EARTH_RADIUS_M

        return hypot(east, north)
    }

    private data class DrSnapshot(
        val anchorLat: Double,
        val anchorLon: Double,
        val anchorTimeMillis: Long,
        val dirEastUnit: Double?,
        val dirNorthUnit: Double?,
        val baselineSpeedMps: Double,
        val speedMps: Double,
        val offsetMeters: Double,
        val lastUpdateTimeMillis: Long
    )

    /**
     * Sliding window of horizontal acceleration values.
     *
     * Stores magnitudes in m/s^2 and keeps only samples within [windowMillis].
     * meanAbs() returns the mean absolute acceleration when the window
     * spans at least windowMillis; otherwise it returns null so callers
     * can ignore short term bursts.
     */
    private class HorizontalAccelWindow(
        private val windowMillis: Long
    ) {
        private data class Sample(
            val timeMillis: Long,
            val value: Float
        )

        private val samples = ArrayDeque<Sample>()

        fun push(timeMillis: Long, value: Float) {
            samples.addLast(Sample(timeMillis, value))
            trim(timeMillis)
        }

        fun clear() {
            samples.clear()
        }

        fun meanAbs(): Float? {
            val size = samples.size
            if (size == 0) {
                return null
            }
            val duration = samples.last().timeMillis - samples.first().timeMillis
            if (duration < windowMillis) {
                return null
            }
            var sum = 0f
            for (s in samples) {
                sum += abs(s.value)
            }
            return sum / size.toFloat()
        }

        private fun trim(nowMillis: Long) {
            while (samples.isNotEmpty()) {
                val oldest = samples.first()
                if (nowMillis - oldest.timeMillis > windowMillis) {
                    samples.removeFirst()
                } else {
                    break
                }
            }
        }
    }
}
