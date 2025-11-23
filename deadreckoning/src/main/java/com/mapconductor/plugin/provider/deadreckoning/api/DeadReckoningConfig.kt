package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

/**
 * DeadReckoning の挙動を調整するための設定値。
 *
 * ■役割
 * - 静止判定や位置ノイズモデルなど、DR エンジンのパラメータを
 *   ライブラリ利用側が必要に応じて調整できるようにする。
 *
 * 既定値はサンプルアプリ向けにチューニングされた値になっており、
 * 通常はそのまま利用して構いません。
 */
data class DeadReckoningConfig(
    /** 静止判定に使う加速度分散の閾値 (m^2/s^4 相当) */
    val staticAccelVarThreshold: Float = 0.02f,
    /** 静止判定に使うジャイロ分散の閾値 ((rad/s)^2) */
    val staticGyroVarThreshold: Float = 0.005f * 0.005f,
    /** 位置ノイズのプロセスノイズ (m^2/s) */
    val processNoisePos: Float = 4f,
    /** GPS 速度とのブレンド係数 */
    val velocityGain: Float = 0.1f,
    /** 静止判定用の移動平均窓サイズ */
    val windowSize: Int = 64
)
