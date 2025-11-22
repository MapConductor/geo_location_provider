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

    /** 位置プロバイダ種別（例: "gps", "dead_reckoning" など）。 */
    val provider: String,

    /** 方位（デバイス北基準の真方位）。 */
    val headingDeg: Double,
    /** 進行方向。Location.bearing 相当。null の場合は未計算 / 不明。 */
    val courseDeg: Double?,
    /** 速度 [m/s]。 */
    val speedMps: Double,

    /** 使用衛星数 / 総数 / 平均 C/N0 など GNSS 系メトリクス。 */
    val gnssUsed: Int,
    val gnssTotal: Int,
    val cn0: Double,

    /** バッテリー残量 [%]。 */
    val batteryPercent: Int,
    val isCharging: Boolean
)

