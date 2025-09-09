package com.mapconductor.plugin.provider.geolocation

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_samples",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["lat", "lon"])
    ]
)
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val provider: String? = null,
    val batteryPct: Int,
    val isCharging: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)
