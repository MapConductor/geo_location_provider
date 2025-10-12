package com.mapconductor.plugin.provider.geolocation._dataselector.condition

data class SelectorCondition(
    val mode: Mode = Mode.ByPeriod,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val limit: Int? = null,
    val minAccuracyM: Float? = null
) {
    enum class Mode { ByPeriod, ByCount }
}
