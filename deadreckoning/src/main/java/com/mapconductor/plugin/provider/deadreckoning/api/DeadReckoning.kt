package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

import java.io.Closeable

/**
 * DeadReckoning の公開インターフェース。
 * - start()/stop() でセンサー購読を開始/停止
 * - GPS真値を submitGpsFix() で渡すとドリフト補正
 * - predict() で指定時刻範囲の予測点を生成して返す
 */
interface DeadReckoning : Closeable {
    fun start()
    fun stop()

    suspend fun submitGpsFix(fix: GpsFix)

    /**
     * [fromMillis, toMillis] の範囲で、UI設定の刻みに従って挿入用の予測点を返します。
     * 刻みの決定は呼び出し側（アプリ側）のスケジューラで行い、
     * DeadReckoning 側は内部状態から各時点の代表値を返す方針です。
     */
    suspend fun predict(fromMillis: Long, toMillis: Long): List<PredictedPoint>

    /** 必須センサー(加速度/ジャイロ)が利用可能か */
    fun isImuCapable(): Boolean

    override fun close() = stop()
}

data class GpsFix(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedMps: Float?
)

data class PredictedPoint(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,      // 推定誤差の近似
    val speedMps: Float?,       // 推定速度
    val horizontalStdM: Float?  // 等方1σ(平面合成) Tier A
)
