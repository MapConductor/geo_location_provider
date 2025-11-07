package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_samples")
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timeMillis: Long = System.currentTimeMillis(),

    val lat: Double,
    val lon: Double,
    val accuracy: Float,

    /** "gps" | "dead_reckoning" など */
    val provider: String,

    /** 方位（デバイス北基準の真方位） */
    val headingDeg: Double,
    /** 進行方向（Location.bearing） */
    val courseDeg: Double,
    /** m/s */
    val speedMps: Double,

    /** 使用衛星数/総数/平均C/N0（GNSS） */
    val gnssUsed: Int,
    val gnssTotal: Int,
    val cn0: Double,

    /** バッテリー */
    val batteryPercent: Int,
    val isCharging: Boolean
)
