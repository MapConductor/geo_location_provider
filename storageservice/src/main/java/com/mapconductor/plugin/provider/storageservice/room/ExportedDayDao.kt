package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ExportedDayDao {

    /**
     * Ensures that a row for the given [day.epochDay] exists.
     *
     * - Uses IGNORE so that existing rows are kept as-is.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(day: ExportedDay)

    /**
     * Returns the oldest day with uploaded == false, or null if none exists.
     */
    @Query("SELECT * FROM exported_days WHERE uploaded = 0 ORDER BY epochDay ASC LIMIT 1")
    suspend fun oldestNotUploaded(): ExportedDay?

    /** Marks the given day as "local ZIP exported". */
    @Query("UPDATE exported_days SET exportedLocal = 1 WHERE epochDay = :d")
    suspend fun markExportedLocal(d: Long)

    /**
     * Marks the given day as "uploaded" and records the Drive file id.
     *
     * - Also clears lastError.
     */
    @Query(
        "UPDATE exported_days SET uploaded = 1, driveFileId = :fileId, lastError = NULL WHERE epochDay = :d"
    )
    suspend fun markUploaded(d: Long, fileId: String?)

    /**
     * Records the latest error message for the given day.
     */
    @Query("UPDATE exported_days SET lastError = :msg WHERE epochDay = :d")
    suspend fun markError(d: Long, msg: String)

    /** Returns the total number of exported_days rows. */
    @Query("SELECT COUNT(*) FROM exported_days")
    suspend fun countAll(): Long

    /**
     * Returns the next day with uploaded == 0 and epochDay > [after], or null if none exists.
     *
     * Intended for:
     * - iterating over backlog days without getting stuck on the same day when
     *   upload is skipped or fails.
     */
    @Query(
        "SELECT * FROM exported_days " +
            "WHERE uploaded = 0 AND epochDay > :after " +
            "ORDER BY epochDay ASC LIMIT 1"
    )
    suspend fun nextNotUploadedAfter(after: Long): ExportedDay?
}
