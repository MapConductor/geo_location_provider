package com.mapconductor.plugin.provider.deadreckoning.impl

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoning
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.GpsFix
import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.PredictedPoint
import com.mapconductor.plugin.provider.deadreckoning.engine.DeadReckoningEngine
import com.mapconductor.plugin.provider.deadreckoning.engine.SensorAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

class DeadReckoningImpl(
    private val appContext: Context,
    private val config: DeadReckoningConfig
) : DeadReckoning {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val engine = DeadReckoningEngine(config)
    private val sensor = SensorAdapter(appContext, scope)

    private var collectingJob: Job? = null
    private var lastTimestampNanos: Long? = null

    override fun isImuCapable(): Boolean = sensor.isImuCapable()

    override fun start() {
        if (collectingJob != null) return
        collectingJob = scope.launch {
            sensor.sensorFlow().collectLatest { s ->
                val prev = lastTimestampNanos
                lastTimestampNanos = s.timestampNanos
                if (prev != null && s.acc != null) {
                    val dt = ((s.timestampNanos - prev).toDouble() / 1_000_000_000.0).toFloat()
                    val dtClamped = max(0.002f, kotlin.math.min(dt, 0.05f)) // 20Hz~500Hz相当のガード
                    engine.onSensor(s.acc, s.gyro, s.mag, dtClamped)
                }
            }
        }
    }

    override fun stop() {
        collectingJob?.cancel()
        collectingJob = null
        lastTimestampNanos = null
    }

    override suspend fun submitGpsFix(fix: GpsFix) {
        engine.submitGpsFix(fix.lat, fix.lon, fix.accuracyM, fix.speedMps)
    }

    override suspend fun predict(fromMillis: Long, toMillis: Long): List<PredictedPoint> {
        // 現在の内部状態スナップショットを単純に返す。
        // 区間内での補間は呼び出し側スケジューラの刻みに合わせて複数回呼ぶ想定。
        val (st, un) = engine.snapshot()
        val hStd = kotlin.math.sqrt(2f * un.sigma2Pos)
        return listOf(
            PredictedPoint(
                timestampMillis = toMillis, // 代表値（呼出側が刻み分の時刻を使ってもOK）
                lat = st.lat,
                lon = st.lon,
                accuracyM = hStd,      // 近似として1σをaccuracy的に流用
                speedMps = st.speedMps,
                horizontalStdM = hStd
            )
        )
    }
}
