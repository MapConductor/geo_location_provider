package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_samples",
    indices = [
        Index(value = ["timeMillis", "provider"], unique = true)
    ]
)
data class LocationSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timeMillis: Long = System.currentTimeMillis(),

    val lat: Double,
    val lon: Double,
    val accuracy: Float,

    /** Provider name (for example "gps", "dead_reckoning"). */
    val provider: String,

    /** Heading in degrees (true north). */
    val headingDeg: Double,
    /** Course in degrees, equivalent to Location.bearing; null when unknown. */
    val courseDeg: Double?,
    /** Speed in meters per second. */
    val speedMps: Double,

    /** GNSS metrics: used satellites, total satellites, and mean C/N0. */
    val gnssUsed: Int,
    val gnssTotal: Int,
    val cn0: Double,

    /** Battery percent. */
    val batteryPercent: Int,
    val isCharging: Boolean
)

