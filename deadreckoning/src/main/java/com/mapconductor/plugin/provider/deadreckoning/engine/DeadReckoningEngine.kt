package com.mapconductor.plugin.provider.deadreckoning.engine

import com.mapconductor.plugin.provider.geolocation.deadreckoning.api.DeadReckoningConfig
import kotlin.math.*

/**
 * Tier A の Dead Reckoning エンジン本体。
 *
 * - 加速度 / ジャイロ / 磁気のセンサ入力を用いて位置・速度・方位を推定する。
 * - ZUPT による静止判定、GPS Fix による補正を組み合わせる。
 * - 位置推定の不確かさ (sigma2Pos) も併せて管理する。
 *
 * 外部には DeadReckoning インターフェース経由で公開され、
 * このクラス自体は internal 実装として扱う。
 */
internal class DeadReckoningEngine(
    private val config: DeadReckoningConfig = DeadReckoningConfig()
) {

    private val state = DrState()
    private val unc = DrUncertainty()

    // 重力推定用のフィルタ
    private var gEst = floatArrayOf(0f, 0f, 9.81f)
    private val gAlpha = 0.90f

    // ZUPT 用しきい値
    private val aVarTh = config.staticAccelVarThreshold       // (m/s^2)^2
    private val wVarTh = config.staticGyroVarThreshold        // (rad/s)^2

    // 位置のプロセスノイズ
    private val qPos = config.processNoisePos                 // m^2/s

    // GPS 速度とのブレンド係数
    private val kV = config.velocityGain

    // 静止判定用ウィンドウ
    private val aWin = CircularWindow(config.windowSize)
    private val wWin = CircularWindow(config.windowSize)

    fun isLikelyStatic(): Boolean {
        val aVar = aWin.variance()
        val wVar = wWin.variance()
        return aVar != null && wVar != null && aVar < aVarTh && wVar < wVarTh
    }

    fun onSensor(acc: FloatArray?, gyro: FloatArray?, mag: FloatArray?, dtSec: Float) {
        // 分散推定用
        acc?.let { aWin.push(norm2(it)) }
        gyro?.let { wWin.push(norm2(it)) }

        // heading を更新（簡易 yaw 推定）
        val heading = if (mag != null) atan2(mag[0].toDouble(), mag[1].toDouble()).toFloat() else state.headingRad
        state.headingRad = wrapAngle(lerpAngle(state.headingRad, heading, 0.05f))

        // 重力成分を引いた水平加速度から速度・位置を更新
        if (acc != null) {
            for (i in 0..2) gEst[i] = gAlpha * gEst[i] + (1 - gAlpha) * acc[i]
            val lin = floatArrayOf(acc[0] - gEst[0], acc[1] - gEst[1], acc[2] - gEst[2])

            val aH = horizontalProjection(lin, state.headingRad) // nav frame (x:east, y:north)
            state.speedMps = max(0f, state.speedMps + (hypot(aH[0], aH[1]) * dtSec))

            // 静止とみなせる場合は徐々に速度を減衰させる
            if (isLikelyStatic()) state.speedMps *= 0.85f

            val dx = state.speedMps * dtSec * cos(state.headingRad)
            val dy = state.speedMps * dtSec * sin(state.headingRad)
            applyDisplacementMeters(dx.toDouble(), dy.toDouble())

            // 位置の不確かさを増加
            unc.sigma2Pos += qPos * dtSec
        }
    }

    fun submitGpsFix(lat: Double, lon: Double, accM: Float?, speedMps: Float?) {
        val r = max(accM ?: 10f, 5f)
        val K = unc.sigma2Pos / (unc.sigma2Pos + r * r)

        // 位置を補正
        state.lat = state.lat + K * (lat - state.lat)
        state.lon = state.lon + K * (lon - state.lon)

        // 速度を補正
        if (speedMps != null) {
            state.speedMps = state.speedMps + kV * (speedMps - state.speedMps)
        }

        // 不確かさを更新
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

    /** センサー統計用の簡易リングバッファ */
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
