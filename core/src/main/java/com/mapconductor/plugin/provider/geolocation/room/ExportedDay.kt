package com.mapconductor.plugin.provider.geolocation.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exported_days")
data class ExportedDay(
    @PrimaryKey val epochDay: Long,          // LocalDate.toEpochDay() （JST）
    val exportedLocal: Boolean = false,      // ZIP 生成済み
    val uploaded: Boolean = false,           // Drive 反映済み
    val driveFileId: String? = null,
    val lastError: String? = null            // 直近の失敗メモ（任意）
)
