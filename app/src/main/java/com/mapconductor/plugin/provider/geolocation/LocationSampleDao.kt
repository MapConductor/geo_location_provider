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

    /** 最新 N 件（降順）を Flow で購読（履歴用） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY id DESC
        LIMIT :limit
        """
    )
    fun latestFlow(limit: Int): Flow<List<LocationSample>>

    /** 最新 1 件（降順） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun latestOne(): LocationSample?

    /** 全件（昇順；GeoJSON整形など時系列用途） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY id ASC
        """
    )
    suspend fun findAll(): List<LocationSample>

    /**
     * 期間抽出（[from, to) ; createdAt は epoch millis）
     * JST の 0:00 切りで使用
     */
    @Query(
        """
        SELECT * FROM location_samples
        WHERE createdAt >= :from AND createdAt < :to
        ORDER BY id ASC
        """
    )
    suspend fun findBetween(from: Long, to: Long): List<LocationSample>

    /** ID リストで削除（アップロード成功時のクリーンアップ用） */
    @Query(
        """
        DELETE FROM location_samples
        WHERE id IN (:ids)
        """
    )
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query(
        """
    SELECT * FROM location_samples
    ORDER BY id DESC
    LIMIT 1
    """
    )
    fun latestOneFlow(): kotlinx.coroutines.flow.Flow<LocationSample?>
}
