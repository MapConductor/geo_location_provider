package com.mapconductor.plugin.provider.geolocation._core.data.room

import androidx.room.*

@Dao
interface ExportedDayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(day: ExportedDay)

    @Query("SELECT * FROM exported_days WHERE uploaded = 0 ORDER BY epochDay ASC LIMIT 1")
    suspend fun oldestNotUploaded(): ExportedDay?

    @Query("UPDATE exported_days SET exportedLocal = 1 WHERE epochDay = :d")
    suspend fun markExportedLocal(d: Long)

    @Query("UPDATE exported_days SET uploaded = 1, driveFileId = :fileId, lastError = NULL WHERE epochDay = :d")
    suspend fun markUploaded(d: Long, fileId: String?)

    @Query("UPDATE exported_days SET lastError = :msg WHERE epochDay = :d")
    suspend fun markError(d: Long, msg: String)
}
