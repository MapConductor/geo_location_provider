package com.mapconductor.plugin.provider.deadreckoning.engine

internal data class DrState(
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var speedMps: Float = 0f,
    var headingRad: Float = 0f
)

/** 等方な位置分散(平面)のみを扱う Tier A の簡易不確かさモデル */
internal data class DrUncertainty(
    var sigma2Pos: Float = 400f // 初期 20m^2 程度（環境に応じて後で調整）
)
