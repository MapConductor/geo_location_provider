package com.mapconductor.plugin.provider.geolocation.fusion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.gps.GpsObservation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * IMS-based (rotation vector + linear acceleration) EKF corrector.
 *
 * State: [east, north, vEast, vNorth].
 * - Propagation uses horizontal linear acceleration in ENU.
 * - GPS performs a position update with measurement noise based on accuracyM.
 *
 * This corrector returns a fused position estimate as the "corrected GPS"
 * observation. It is intentionally conservative and can be tuned via config.
 */
class ImsEkfCorrectionEngine(
    context: Context,
    private val configProvider: () -> ImsEkfConfig
) : LifecycleAwareGpsCorrectionEngine, SensorEventListener {

    private companion object {
        private const val TAG = "ImsEkfCorrectionEngine"
        private const val EARTH_RADIUS_M = 6371000.0
        private const val MIN_GPS_STD_M = 2.0
        private const val MIN_OUTPUT_STD_M = 1.0
        private const val SPEED_FOR_BEARING_MPS = 0.2
    }

    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val lock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var rotationMatrix9: FloatArray? = null
    private var hasRotation: Boolean = false

    private var lastAccelEastMps2: Double = 0.0
    private var lastAccelNorthMps2: Double = 0.0
    private var lastImuTimeNs: Long? = null

    private var biasEastMps2: Double = 0.0
    private var biasNorthMps2: Double = 0.0

    private var originLat: Double? = null
    private var originLon: Double? = null
    private var originLatRad: Double? = null

    private var xEastM: Double = 0.0
    private var yNorthM: Double = 0.0
    private var vEastMps: Double = 0.0
    private var vNorthMps: Double = 0.0
    private val p: DoubleArray = DoubleArray(16)

    private var lastOutputTimeNs: Long? = null
    private var lastOutputEastM: Double = 0.0
    private var lastOutputNorthM: Double = 0.0

    @Volatile
    private var expectedGpsIntervalMs: Long = 1000L

    @Volatile
    private var started: Boolean = false

    override fun start() {
        val cfg = ImsEkfConfig.clamp(configProvider())
        if (!cfg.enabled) {
            return
        }

        synchronized(lock) {
            if (started) return

            if (!isImuCapableLocked()) {
                Log.w(TAG, "start: missing required sensors, IMS disabled")
                return
            }

            started = true
            cfg.gpsIntervalMsOverride?.let { expectedGpsIntervalMs = it }

            val t =
                HandlerThread(
                    "ImsEkf",
                    Process.THREAD_PRIORITY_MORE_FAVORABLE
                )
            t.start()
            thread = t
            handler = Handler(t.looper)

            val rotation =
                sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                    ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            val linearAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

            if (rotation != null) {
                sensorManager.registerListener(this, rotation, cfg.imuSensorDelay, handler)
            }
            if (linearAcc != null) {
                sensorManager.registerListener(this, linearAcc, cfg.imuSensorDelay, handler)
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false

            try {
                sensorManager.unregisterListener(this)
            } catch (_: Throwable) {
                // Ignore.
            }

            handler = null
            thread?.quitSafely()
            thread = null

            resetLocked()
        }
    }

    override fun correct(observation: GpsObservation, hint: GpsCorrectionHint): GpsObservation {
        val cfg = ImsEkfConfig.clamp(configProvider())
        if (!cfg.enabled) {
            if (started) {
                stop()
            }
            return observation
        }

        if (!started) {
            start()
        }

        expectedGpsIntervalMs = cfg.gpsIntervalMsOverride ?: hint.updateIntervalMs

        val tNs = observation.elapsedRealtimeNanos
        if (tNs == null) {
            return observation
        }

        synchronized(lock) {
            if (!started) {
                return observation
            }

            ensureInitializedLocked(observation, hint)
            propagateToLocked(tNs, cfg)
            gpsUpdateLocked(observation, cfg)

            val tauSec = cfg.allowedLatencyMs.toDouble() / 1000.0
            val (outEast, outNorth) =
                if (tauSec > 0.0) {
                    val prevT = lastOutputTimeNs
                    val dt = prevT?.let { (tNs - it).toDouble() / 1_000_000_000.0 } ?: 0.0
                    val alpha = if (dt > 0.0) (1.0 - exp(-dt / tauSec)).coerceIn(0.0, 1.0) else 1.0
                    val east = lastOutputEastM + alpha * (xEastM - lastOutputEastM)
                    val north = lastOutputNorthM + alpha * (yNorthM - lastOutputNorthM)
                    lastOutputTimeNs = tNs
                    lastOutputEastM = east
                    lastOutputNorthM = north
                    east to north
                } else {
                    lastOutputTimeNs = tNs
                    lastOutputEastM = xEastM
                    lastOutputNorthM = yNorthM
                    xEastM to yNorthM
                }

            val (lat, lon) = enuToLatLonLocked(outEast, outNorth)

            val speed = hypot(vEastMps, vNorthMps).coerceAtLeast(0.0).coerceAtMost(cfg.maxSpeedMps)
            val courseDeg =
                if (speed >= SPEED_FOR_BEARING_MPS) {
                    bearingDeg(eastM = vEastMps, northM = vNorthMps)
                } else {
                    Double.NaN
                }

            val accM = positionStdMetersLocked().coerceAtLeast(MIN_OUTPUT_STD_M).toFloat()

            return observation.copy(
                lat = lat,
                lon = lon,
                accuracyM = accM,
                speedMps = speed.toFloat(),
                bearingDeg = courseDeg.takeIf { !it.isNaN() }?.toFloat(),
                hasBearing = !courseDeg.isNaN()
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val cfg = ImsEkfConfig.clamp(configProvider())
        if (!cfg.enabled) {
            return
        }

        synchronized(lock) {
            if (!started) {
                return
            }

            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                    val r = rotationMatrix9 ?: FloatArray(9).also { rotationMatrix9 = it }
                    SensorManager.getRotationMatrixFromVector(r, event.values)
                    hasRotation = true
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val tNs = event.timestamp
                    val ax = event.values.getOrNull(0) ?: return
                    val ay = event.values.getOrNull(1) ?: return
                    val az = event.values.getOrNull(2) ?: 0f

                    val (east, north) =
                        if (hasRotation) {
                            val r = rotationMatrix9 ?: return
                            val wx = r[0] * ax + r[1] * ay + r[2] * az
                            val wy = r[3] * ax + r[4] * ay + r[5] * az
                            wx.toDouble() to wy.toDouble()
                        } else {
                            ax.toDouble() to ay.toDouble()
                        }

                    val dtSec =
                        lastImuTimeNs?.let { prev ->
                            val maxDt = maxPropagationDtSec()
                            ((tNs - prev).toDouble() / 1_000_000_000.0).coerceIn(0.0, maxDt)
                        } ?: 0.0

                    val accelMag = hypot(east, north)
                    val isStaticByAccel = accelMag < cfg.staticAccelThresholdMps2
                    if (isStaticByAccel) {
                        biasEastMps2 =
                            (1.0 - cfg.biasUpdateAlpha) * biasEastMps2 + cfg.biasUpdateAlpha * east
                        biasNorthMps2 =
                            (1.0 - cfg.biasUpdateAlpha) * biasNorthMps2 + cfg.biasUpdateAlpha * north
                    }

                    lastAccelEastMps2 = east - biasEastMps2
                    lastAccelNorthMps2 = north - biasNorthMps2

                    if (dtSec > 0.0) {
                        propagateLocked(dtSec, cfg)
                    }
                    lastImuTimeNs = tNs
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun ensureInitializedLocked(observation: GpsObservation, hint: GpsCorrectionHint) {
        if (originLat != null && originLon != null) {
            return
        }

        originLat = observation.lat
        originLon = observation.lon
        originLatRad = Math.toRadians(observation.lat)

        xEastM = 0.0
        yNorthM = 0.0

        val courseDeg =
            observation.bearingDeg?.toDouble()
                ?: hint.headingTrueDeg?.toDouble()
        val speed = max(0.0, hint.speedMps.toDouble())

        if (courseDeg != null) {
            val rad = Math.toRadians(courseDeg)
            vEastMps = sin(rad) * speed
            vNorthMps = cos(rad) * speed
        } else {
            vEastMps = 0.0
            vNorthMps = 0.0
        }

        val std = max(MIN_GPS_STD_M, observation.accuracyM.toDouble())
        setIdentity4x4(p)
        p[0] = std * std
        p[5] = std * std
        p[10] = 10.0 * 10.0
        p[15] = 10.0 * 10.0

        lastOutputEastM = xEastM
        lastOutputNorthM = yNorthM
        lastOutputTimeNs = observation.elapsedRealtimeNanos
    }

    private fun resetLocked() {
        rotationMatrix9 = null
        hasRotation = false

        lastAccelEastMps2 = 0.0
        lastAccelNorthMps2 = 0.0
        lastImuTimeNs = null

        biasEastMps2 = 0.0
        biasNorthMps2 = 0.0

        originLat = null
        originLon = null
        originLatRad = null

        xEastM = 0.0
        yNorthM = 0.0
        vEastMps = 0.0
        vNorthMps = 0.0
        p.fill(0.0)

        lastOutputTimeNs = null
        lastOutputEastM = 0.0
        lastOutputNorthM = 0.0
    }

    private fun propagateToLocked(tNs: Long, cfg: ImsEkfConfig) {
        val prev = lastImuTimeNs ?: return
        if (tNs <= prev) {
            return
        }
        val dtSec = ((tNs - prev).toDouble() / 1_000_000_000.0).coerceIn(0.0, maxPropagationDtSec())
        if (dtSec > 0.0) {
            propagateLocked(dtSec, cfg)
            lastImuTimeNs = tNs
        }
    }

    private fun propagateLocked(dt: Double, cfg: ImsEkfConfig) {
        val dt2 = dt * dt

        xEastM += vEastMps * dt + 0.5 * lastAccelEastMps2 * dt2
        yNorthM += vNorthMps * dt + 0.5 * lastAccelNorthMps2 * dt2
        vEastMps += lastAccelEastMps2 * dt
        vNorthMps += lastAccelNorthMps2 * dt

        val speed = hypot(vEastMps, vNorthMps)
        if (speed > cfg.maxSpeedMps && speed > 0.0) {
            val s = cfg.maxSpeedMps / speed
            vEastMps *= s
            vNorthMps *= s
        }

        val f = doubleArrayOf(
            1.0, 0.0, dt, 0.0,
            0.0, 1.0, 0.0, dt,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )

        val fp = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) {
                    s += f[i * 4 + k] * p[k * 4 + j]
                }
                fp[i * 4 + j] = s
            }
        }

        val pNew = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) {
                    s += fp[i * 4 + k] * f[j * 4 + k]
                }
                pNew[i * 4 + j] = s
            }
        }

        val q = cfg.accelNoiseStdMps2 * cfg.accelNoiseStdMps2
        val q11 = 0.25 * dt2 * dt2 * q
        val q13 = 0.5 * dt2 * dt * q
        val q33 = dt2 * q

        pNew[0] += q11
        pNew[5] += q11
        pNew[10] += q33
        pNew[15] += q33
        pNew[2] += q13
        pNew[8] += q13
        pNew[7] += q13
        pNew[13] += q13

        for (i in 0 until 16) {
            p[i] = pNew[i]
        }
    }

    private fun gpsUpdateLocked(observation: GpsObservation, cfg: ImsEkfConfig) {
        val (zEast, zNorth) = latLonToEnuLocked(observation.lat, observation.lon)

        val std =
            max(MIN_GPS_STD_M, observation.accuracyM.toDouble()) * cfg.gpsAccuracyMultiplier
        val r = std * std

        val s00 = p[0] + r
        val s01 = p[1]
        val s10 = p[4]
        val s11 = p[5] + r
        val det = s00 * s11 - s01 * s10
        if (abs(det) < 1e-12) {
            return
        }

        val inv00 = s11 / det
        val inv01 = -s01 / det
        val inv10 = -s10 / det
        val inv11 = s00 / det

        val k = DoubleArray(8)
        for (i in 0 until 4) {
            val p0 = p[i * 4 + 0]
            val p1 = p[i * 4 + 1]
            k[i * 2 + 0] = p0 * inv00 + p1 * inv10
            k[i * 2 + 1] = p0 * inv01 + p1 * inv11
        }

        val y0 = zEast - xEastM
        val y1 = zNorth - yNorthM

        xEastM += k[0] * y0 + k[1] * y1
        yNorthM += k[2] * y0 + k[3] * y1
        vEastMps += k[4] * y0 + k[5] * y1
        vNorthMps += k[6] * y0 + k[7] * y1

        val a = DoubleArray(16)
        setIdentity4x4(a)
        for (i in 0 until 4) {
            a[i * 4 + 0] -= k[i * 2 + 0]
            a[i * 4 + 1] -= k[i * 2 + 1]
        }

        val ap = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (t in 0 until 4) {
                    s += a[i * 4 + t] * p[t * 4 + j]
                }
                ap[i * 4 + j] = s
            }
        }

        val pNew = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (t in 0 until 4) {
                    s += ap[i * 4 + t] * a[j * 4 + t]
                }
                pNew[i * 4 + j] = s
            }
        }

        val kkT = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val k0i = k[i * 2 + 0]
                val k1i = k[i * 2 + 1]
                val k0j = k[j * 2 + 0]
                val k1j = k[j * 2 + 1]
                kkT[i * 4 + j] = k0i * k0j + k1i * k1j
            }
        }

        for (i in 0 until 16) {
            p[i] = pNew[i] + r * kkT[i]
        }
    }

    private fun positionStdMetersLocked(): Double {
        val s = max(p[0], p[5]).coerceAtLeast(0.0)
        return sqrt(s)
    }

    private fun maxPropagationDtSec(): Double {
        val base = max(250L, expectedGpsIntervalMs)
        return (base.toDouble() / 1000.0).coerceAtMost(2.0) * 2.0
    }

    private fun isImuCapableLocked(): Boolean {
        val rotation =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val linearAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        return linearAcc != null && rotation != null
    }

    private fun latLonToEnuLocked(lat: Double, lon: Double): Pair<Double, Double> {
        val lat0 = originLat ?: return 0.0 to 0.0
        val lon0 = originLon ?: return 0.0 to 0.0
        val lat0Rad = originLatRad ?: Math.toRadians(lat0)

        val dLat = Math.toRadians(lat - lat0)
        val dLon = Math.toRadians(lon - lon0)

        val north = dLat * EARTH_RADIUS_M
        val east = dLon * EARTH_RADIUS_M * cos(lat0Rad)
        return east to north
    }

    private fun enuToLatLonLocked(eastM: Double, northM: Double): Pair<Double, Double> {
        val lat0 = originLat ?: return 0.0 to 0.0
        val lon0 = originLon ?: return 0.0 to 0.0
        val lat0Rad = originLatRad ?: Math.toRadians(lat0)

        val dLat = northM / EARTH_RADIUS_M
        val dLon =
            if (abs(cos(lat0Rad)) < 1e-6) {
                0.0
            } else {
                eastM / (EARTH_RADIUS_M * cos(lat0Rad))
            }

        val lat = lat0 + Math.toDegrees(dLat)
        val lon = lon0 + Math.toDegrees(dLon)
        return lat to lon
    }

    private fun setIdentity4x4(m: DoubleArray) {
        for (i in 0 until 16) {
            m[i] = 0.0
        }
        m[0] = 1.0
        m[5] = 1.0
        m[10] = 1.0
        m[15] = 1.0
    }

    private fun bearingDeg(eastM: Double, northM: Double): Double {
        if (eastM == 0.0 && northM == 0.0) {
            return Double.NaN
        }
        val deg = Math.toDegrees(atan2(eastM, northM))
        val normalized = ((deg % 360.0) + 360.0) % 360.0
        return if (normalized.isNaN()) Double.NaN else normalized
    }
}
