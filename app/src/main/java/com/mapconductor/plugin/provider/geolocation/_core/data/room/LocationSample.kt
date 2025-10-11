package com.mapconductor.plugin.provider.geolocation._core.data.room

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

    // ▼ 追加（すべて nullable：既存データ互換）
    /** 端末の向き（真北 = 0°…360°） */
    val headingDeg: Float? = null,
    /** 進行方向（Location の bearing 等） */
    val courseDeg: Float? = null,
    /** 速度（m/s） */
    val speedMps: Float? = null,
    /** usedInFix の衛星数 */
    val gnssUsed: Int? = null,
    /** 見えている総衛星数 */
    val gnssTotal: Int? = null,
    /** C/N0（dB-Hz）の平均値（usedInFix 対象があればそちらの平均） */
    val gnssCn0Mean: Float? = null,

    val createdAt: Long = System.currentTimeMillis()
)
