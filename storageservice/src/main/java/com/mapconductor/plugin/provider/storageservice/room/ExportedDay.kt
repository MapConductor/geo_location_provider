package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exported_days")
data class ExportedDay(
    /** LocalDate.toEpochDay() と同じ基準（UTC 1970-01-01 からの日数）。 */
    @PrimaryKey val epochDay: Long,
    /** ローカル ZIP が生成済みかどうか。 */
    val exportedLocal: Boolean = false,
    /** Drive へのアップロードが完了しているかどうか。 */
    val uploaded: Boolean = false,
    /** Drive 側のファイル ID（必要に応じて記録）。 */
    val driveFileId: String? = null,
    /** 直近の失敗メモ（任意）。null の場合はエラー無し。 */
    val lastError: String? = null
)

