package com.mapconductor.plugin.provider.geolocation.deadreckoning.engine

import kotlin.math.*

/**
 * Tier A の簡易エンジン：
 * - ジャイロ+地磁気を単純補間した方位で線加速度を水平面に射影
 * - 数値積分で速度・位置を更新
 * - ZUPT: 静止推定時に速度を0へ漸近
 * - αβ風補正: GPS Fix の到来で位置分散を縮小
 *
 * ※位置(lat, lon)更新は簡易な「メートル→度」換算を採用（中緯度想定）
 *   高精度が必要になればECEF/ENU等へ変更する
 */
internal class DeadReckoningEngine {

    private val state = DrState()
    private val unc = DrUncertainty()

    // 重力除去の簡易ローパス
    private var gEst = floatArrayOf(0f, 0f, 9.81f)
    private val gAlpha = 0.90f

    // ZUPTしきい値（初期値）
    private val aVarTh = 0.02f       // (m/s^2)^2
    private val wVarTh = 0.005f * 0.005f // (rad/s)^2

    // プロセスノイズ（位置）
    private val qPos = 4f // m^2/s（経験則）

    // 速度補正の弱いゲイン（GPSのspeedMpsがある時のみ使用）
    private val kV = 0.1f

    // 直近のセンサー分散推定用の窓（とても簡易な平滑）
    private val aWin = CircularWindow(64)
    private val wWin = CircularWindow(64)

    fun isLikelyStatic(): Boolean {
        val aVar = aWin.variance()
        val wVar = wWin.variance()
        return aVar != null && wVar != null && aVar < aVarTh && wVar < wVarTh
    }

    fun onSensor(acc: FloatArray?, gyro: FloatArray?, mag: FloatArray?, dtSec: Float) {
        // 分散更新
        acc?.let { aWin.push(norm2(it)) }
        gyro?.let { wWin.push(norm2(it)) }

        // 方位（超簡易：磁気のxyから方位、ジャイロは分散評価にのみ使用）
        val heading = if (mag != null) atan2(mag[0].toDouble(), mag[1].toDouble()).toFloat() else state.headingRad
        state.headingRad = wrapAngle(lerpAngle(state.headingRad, heading, 0.05f))

        // 重力除去（ローパスで重力推定）
        if (acc != null) {
            for (i in 0..2) gEst[i] = gAlpha * gEst[i] + (1 - gAlpha) * acc[i]
            val lin = floatArrayOf(acc[0] - gEst[0], acc[1] - gEst[1], acc[2] - gEst[2])

            // 水平面に投影（上下成分を捨てる簡易版）
            val aH = horizontalProjection(lin, state.headingRad) // m/s^2 on nav frame (x: east, y: north)
            // 速度更新
            state.speedMps = max(0f, state.speedMps + (hypot(aH[0], aH[1]) * dtSec))

            // 静止っぽければ速度0へ寄せる
            if (isLikelyStatic()) state.speedMps *= 0.85f

            // 位置更新（速度×方位）
            val dx = state.speedMps * dtSec * cos(state.headingRad)
            val dy = state.speedMps * dtSec * sin(state.headingRad)
            applyDisplacementMeters(dx.toDouble(), dy.toDouble())

            // 不確かさ（位置）を拡大
            unc.sigma2Pos += qPos * dtSec
        }
    }

    fun submitGpsFix(lat: Double, lon: Double, accM: Float?, speedMps: Float?) {
        val r = max(accM ?: 10f, 5f) // 安全最小 5m
        val K = unc.sigma2Pos / (unc.sigma2Pos + r * r)
        // 位置補正（緯度経度差分にせず、簡易に目標へ線形に寄せる）
        state.lat = state.lat + K * (lat - state.lat)
        state.lon = state.lon + K * (lon - state.lon)
        // 速度補正（任意）
        if (speedMps != null) state.speedMps = state.speedMps + kV * (speedMps - state.speedMps)
        // 分散縮小
        unc.sigma2Pos = (1 - K) * unc.sigma2Pos
    }

    fun snapshot(): Pair<DrState, DrUncertainty> = state.copy() to unc.copy()

    private fun applyDisplacementMeters(dxEast: Double, dyNorth: Double) {
        // 緯度経度へ反映（WGS84 簡易換算）
        val latRad = Math.toRadians(state.lat)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(latRad)
        state.lat += (dyNorth / metersPerDegLat)
        state.lon += (dxEast / metersPerDegLon)
    }

    private fun norm2(v: FloatArray): Float = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
    private fun horizontalProjection(a: FloatArray, headingRad: Float): FloatArray {
        // heading に沿って水平2Dに落とす超簡易版（実運用では回転行列を用いる）
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

    /** 分散推定のための簡易リングバッファ */
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
