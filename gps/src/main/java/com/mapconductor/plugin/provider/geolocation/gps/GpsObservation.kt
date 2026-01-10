package com.mapconductor.plugin.provider.geolocation.gps

/**
 * Represents one GPS position observation with optional GNSS metadata.
 *
 * This type is independent from platform callbacks so that core logic
 * can work with a stable domain model.
 */
data class GpsObservation(
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
    val hasBearing: Boolean,
    val gnssUsed: Int?,
    val gnssTotal: Int?,
    val cn0Mean: Double?
)

