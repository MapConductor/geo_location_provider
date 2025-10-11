package com.mapconductor.plugin.provider.geolocation._core.util

/**
 * ログタグの集中管理。必要に応じて増やしてください。
 * 実際の出力は P2/P3 以降で行えばOK（P1は置くだけ）。
 */
object LogTags {
    const val WORKER = "GLP-Worker"
    const val DRIVE  = "GLP-Drive"
    const val NATIVE = "GLP-Native"
}