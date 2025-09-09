package com.mapconductor.plugin.provider.geolocation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sample: LocationSample): Long

    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    fun latest(limit: Int): Flow<List<LocationSample>>

    // ★ 追加：最新1件だけを流す
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    fun latestOne(): Flow<LocationSample?>
}