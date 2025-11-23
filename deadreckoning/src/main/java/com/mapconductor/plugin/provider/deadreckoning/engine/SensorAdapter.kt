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
        val mag: FloatArray?    // μT
    )

    fun isImuCapable(): Boolean {
        val hasAcc = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        return hasAcc && hasGyro
    }

    /**
     * SENSOR_DELAY_GAME を標準、画面OFFかつ低運動で SENSOR_DELAY_NORMAL に落とすのは
     * 呼び出し側(上位)の状態判断でもよいが、まずは GAME 固定で配信しておき、
     * 将来ここに切替ロジックを入れられるようにする。
     */
    fun sensorFlow() = callbackFlow<Sample> {
        val acc = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag  = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val listener = object : SensorEventListener {
            var lastAcc: FloatArray? = null
            var lastGyro: FloatArray? = null
            var lastMag: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> lastAcc = event.values.clone()
                    Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.clone()
                }
                trySend(
                    Sample(
                        timestampNanos = event.timestamp,
                        acc = lastAcc, gyro = lastGyro, mag = lastMag
                    )
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        acc?.also { manager?.registerListener(listener, it, rate) }
        gyro?.also { manager?.registerListener(listener, it, rate) }
        mag?.also  { manager?.registerListener(listener, it, rate) }

        awaitClose {
            manager?.unregisterListener(listener)
        }
    }
}
