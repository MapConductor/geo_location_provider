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

    // 最新N件（降順）をFlowで配信（履歴表示）
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    fun latest(limit: Int): Flow<List<LocationSample>>

    // 最新1件（降順）をFlowで配信（サービス状態表示）
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    fun latestOne(): Flow<LocationSample?>

    // 手動エクスポート等の同期取得：最新N件（降順）
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun latestList(limit: Int): List<LocationSample>

    // 全件（昇順）を同期取得（limitなしの手動エクスポート用）
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt ASC
    """)
    suspend fun findAllAsc(): List<LocationSample>

    // 指定エポックより前（昇順）…日次0:00で切る
    @Query("""
        SELECT * FROM location_samples
        WHERE createdAt < :cutoffEpochMillis
        ORDER BY createdAt ASC
    """)
    suspend fun findBefore(cutoffEpochMillis: Long): List<LocationSample>

    // エクスポート済みIDの一括削除
    @Query("DELETE FROM location_samples WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int
}
