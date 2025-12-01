package com.mapconductor.plugin.provider.deadreckoning.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class SensorAdapter(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val manager by lazy { context.getSystemService(SensorManager::class.java) }

    data class Sample(
        val timestampNanos: Long,
        val acc: FloatArray?,   // m/s^2
        val gyro: FloatArray?,  // rad/s
        val mag: FloatArray?,   // microtesla
        val rotVec: FloatArray? // unit quaternion-like rotation vector
    )

    fun isImuCapable(): Boolean {
        val hasAcc = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        return hasAcc && hasGyro
    }

    /**
     * Provide a Flow of combined accelerometer/gyro/magnetometer samples.
     *
     * Implementation detail:
     * - Uses SENSOR_DELAY_GAME as default rate.
     * - For simplicity, screen-off / low-motion optimizations are left as future work.
     */
    fun sensorFlow() = callbackFlow<Sample> {
        val acc = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag  = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rot  = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            var lastAcc: FloatArray? = null
            var lastGyro: FloatArray? = null
            var lastMag: FloatArray? = null
            var lastRot: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> lastAcc = event.values.clone()
                    Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.clone()
                    Sensor.TYPE_ROTATION_VECTOR -> lastRot = event.values.clone()
                }
                trySend(
                    Sample(
                        timestampNanos = event.timestamp,
                        acc = lastAcc,
                        gyro = lastGyro,
                        mag = lastMag,
                        rotVec = lastRot
                    )
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        acc?.also { manager?.registerListener(listener, it, rate) }
        gyro?.also { manager?.registerListener(listener, it, rate) }
        mag?.also  { manager?.registerListener(listener, it, rate) }
        rot?.also  { manager?.registerListener(listener, it, rate) }

        awaitClose {
            manager?.unregisterListener(listener)
        }
    }
}
