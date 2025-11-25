package com.mapconductor.plugin.provider.geolocation.util

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.round

/**
 * Helper around rotation vector sensors that provides device heading in degrees (0..360).
 *
 * It prefers TYPE_ROTATION_VECTOR and falls back to TYPE_GEOMAGNETIC_ROTATION_VECTOR.
 * Declination can be updated from location data so that magnetic heading is converted
 * to true heading.
 */
internal class HeadingSensor(
    private val context: Context
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    private var lastAzimuthDegMag: Float = Float.NaN
    @Volatile
    private var declinationDeg: Float = 0f

    private var started = false

    fun start() {
        if (started) return
        started = true
        val primary = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val fallback = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val sensor = primary ?: fallback ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!started) return
        started = false
        sm.unregisterListener(this)
    }

    /** Update magnetic declination from location and time. */
    fun updateDeclination(lat: Double, lon: Double, altitudeMeters: Float, timeMillis: Long) {
        val field = GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            altitudeMeters,
            timeMillis
        )
        declinationDeg = field.declination
    }

    /**
     * Returns true heading in degrees (0..360), or null if it is not yet available.
     *
     * The result is rounded to 0.1 degree.
     */
    fun headingTrueDeg(): Float? {
        val mag = lastAzimuthDegMag
        if (mag.isNaN()) return null
        var trueDeg = mag + declinationDeg
        while (trueDeg < 0f) trueDeg += 360f
        while (trueDeg >= 360f) trueDeg -= 360f
        return (round(trueDeg * 10f) / 10f)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) {
            return
        }

        val R = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(R, event.values)
        SensorManager.getOrientation(R, orientation)

        // azimuth [rad] -> [deg] and normalize to 0..360
        var deg = (orientation[0] * 180f / PI.toFloat())
        if (deg < 0) deg += 360f
        lastAzimuthDegMag = deg
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

