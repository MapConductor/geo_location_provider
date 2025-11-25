package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LocationSampleDao {

    /**
     * Inserts one LocationSample row.
     *
     * - Uses [OnConflictStrategy.REPLACE], replacing existing rows on key collision.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: LocationSample): Long

    /**
     * Watches the latest [limit] rows as a Flow.
     *
     * - When multiple rows share the same timeMillis, provider and id are used
     *   as secondary keys to keep ordering stable.
     */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY
          timeMillis DESC,
          CASE WHEN provider = 'gps' THEN 0 ELSE 1 END,
          id DESC
        LIMIT :limit
        """
    )
    fun latestFlow(limit: Int): Flow<List<LocationSample>>

    /** Watches the most recent row as a Flow. */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis DESC, id DESC
        LIMIT 1
        """
    )
    fun latestOneFlow(): Flow<LocationSample?>

    /** Returns all rows ordered by timeMillis ASC, id ASC (for time series use). */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis ASC, id ASC
        """
    )
    suspend fun findAll(): List<LocationSample>

    /**
     * Returns rows in the range [from, to) ordered by timeMillis ASC, id ASC.
     *
     * - Intended for date based extraction such as JST 0:00 boundaries.
     */
    @Query(
        """
        SELECT * FROM location_samples
        WHERE timeMillis >= :from AND timeMillis < :to
        ORDER BY timeMillis ASC, id ASC
        """
    )
    suspend fun findBetween(from: Long, to: Long): List<LocationSample>

    /**
     * Returns up to [softLimit] rows in the range [from, to) ordered by timeMillis ASC.
     *
     * - Intended for daily export processing by workers.
     */
    @Query(
        """
        SELECT *
        FROM location_samples
        WHERE timeMillis >= :from AND timeMillis < :to
        ORDER BY timeMillis ASC
        LIMIT :softLimit
        """
    )
    suspend fun getInRangeAscOnce(
        from: Long,
        to: Long,
        softLimit: Int
    ): List<LocationSample>

    /** Returns the total row count. */
    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun countAll(): Long

    /**
     * Deletes the given rows in a batch.
     *
     * - Typically used for cleanup after successful upload.
     */
    @Delete
    suspend fun deleteAll(items: List<LocationSample>)
}

