package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ExportedDayDao {

    /**
     * 指定された日付 [day.epochDay] の ExportedDay レコードを ensure する。
     *
     * - 既に同じ epochDay のレコードがある場合は何もしない（IGNORE）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(day: ExportedDay)

    /**
     * 「まだ uploaded=false の中で最も古い日」を返す。
     *
     * - 対象が存在しない場合は null を返す。
     */
    @Query("SELECT * FROM exported_days WHERE uploaded = 0 ORDER BY epochDay ASC LIMIT 1")
    suspend fun oldestNotUploaded(): ExportedDay?

    /** 指定された日付を「ローカル ZIP 出力済み」としてマークする。 */
    @Query("UPDATE exported_days SET exportedLocal = 1 WHERE epochDay = :d")
    suspend fun markExportedLocal(d: Long)

    /**
     * 指定された日付を「アップロード済み」としてマークし、Drive の fileId 等を記録する。
     *
     * - 同時に lastError はクリアされる。
     */
    @Query(
        "UPDATE exported_days SET uploaded = 1, driveFileId = :fileId, lastError = NULL WHERE epochDay = :d"
    )
    suspend fun markUploaded(d: Long, fileId: String?)

    /**
     * 指定された日付に対して、直近のエラーメッセージを記録する。
     */
    @Query("UPDATE exported_days SET lastError = :msg WHERE epochDay = :d")
    suspend fun markError(d: Long, msg: String)
}
