package com.mapconductor.plugin.provider.geolocation._core.data.room

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
        ORDER BY id DESC
        LIMIT :limit
    """)
    fun latestFlow(limit: Int): Flow<List<LocationSample>>

    /** 全件（降順）を Flow で購読（可変表示用） */
    @Query("""
        SELECT * FROM location_samples
        ORDER BY id DESC
    """)
    fun latestFlowAll(): Flow<List<LocationSample>>

    /** 最新 1 件（降順） */
    @Query("""
        SELECT * FROM location_samples
        ORDER BY id DESC
        LIMIT 1
    """)
    suspend fun latestOne(): LocationSample?

    /** 全件（昇順；GeoJSON整形など時系列用途） */
    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt ASC, id ASC
    """)
    suspend fun findAll(): List<LocationSample>

    /**
     * 期間抽出（[from, to) ; createdAt は epoch millis）
     * JST の 0:00 切りで使用
     */
    @Query("""
        SELECT * FROM location_samples
        WHERE createdAt >= :from AND createdAt < :to
        ORDER BY createdAt ASC, id ASC
    """)
    suspend fun findBetween(from: Long, to: Long): List<LocationSample>

    /** ID リストで削除（アップロード成功時のクリーンアップ用） */
    @Query("""
        DELETE FROM location_samples
        WHERE id IN (:ids)
    """)
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("""
        SELECT * FROM location_samples
        ORDER BY id DESC
        LIMIT 1
    """)
    fun latestOneFlow(): Flow<LocationSample?>

    @Query("""
        SELECT * FROM location_samples
        ORDER BY createdAt DESC
    """)
    fun observeAll(): Flow<List<LocationSample>>

    @Query(
        """
        SELECT * FROM location_samples
        WHERE (:from IS NULL OR createdAt >= :from)
          AND (:to   IS NULL OR createdAt  < :to)
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    fun getInRangeNewestFirst(
        from: Long?,
        to: Long?,
        limit: Int
    ): Flow<List<LocationSample>>

    @Query(
        """
        SELECT * FROM location_samples
        WHERE (:from IS NULL OR createdAt >= :from)
          AND (:to   IS NULL OR createdAt  < :to)
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    fun getInRangeOldestFirst(
        from: Long?,
        to: Long?,
        limit: Int
    ): Flow<List<LocationSample>>

    /**
     * 間隔リサンプリング用：範囲を ASC で一括取得（メモリで最近傍選定）。
     * softLimit は安全側に多めを指定する想定。
     */
    @Query(
        """
        SELECT * FROM location_samples
        WHERE (:from IS NULL OR createdAt >= :from)
          AND (:to   IS NULL OR createdAt  < :to)
        ORDER BY createdAt ASC
        LIMIT :softLimit
        """
    )
    suspend fun getInRangeAscOnce(
        from: Long?,
        to: Long?,
        softLimit: Int
    ): List<LocationSample>
}
