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

    @Query("""
    SELECT * FROM location_samples
    ORDER BY createdAt DESC
    LIMIT :limit
    """)

    suspend fun latestList(limit: Int): List<LocationSample>
    @Query("""
        SELECT * FROM location_samples
        WHERE createdAt < :cutoffEpochMillis
        ORDER BY createdAt ASC
    """)

    suspend fun findBefore(cutoffEpochMillis: Long): List<LocationSample>

    @Query("DELETE FROM location_samples WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int}