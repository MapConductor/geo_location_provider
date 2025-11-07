package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationSampleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sample: LocationSample): Long

    /** 最新 N 件（Flow）。同一時刻がある場合でも順序が安定するよう id を二次キーに使用 */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis DESC
        LIMIT :limit
        """
    )
    fun latestFlow(limit: Int): Flow<List<LocationSample>>

    /** 最新 1 件（Flow） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis DESC
        LIMIT 1
        """
    )
    fun latestOneFlow(): Flow<LocationSample?>
}
