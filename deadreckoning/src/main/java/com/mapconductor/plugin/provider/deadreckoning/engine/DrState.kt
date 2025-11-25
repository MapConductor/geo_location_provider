package com.mapconductor.plugin.provider.deadreckoning.engine

internal data class DrState(
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var speedMps: Float = 0f,
    var headingRad: Float = 0f
)

/** Simple uncertainty model for Tier A with isotropic horizontal position variance only. */
internal data class DrUncertainty(
    var sigma2Pos: Float = 400f // Roughly 20 m^2 initial variance (tuned later per environment).
)

