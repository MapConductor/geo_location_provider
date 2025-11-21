package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: LocationSample): Long

    /**
     * 最新 N 件を Flow で監視する。
     * timeMillis が同一のレコードがある場合でも、provider と id を二次キーとして順序を安定させる。
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

    /** 最後の 1 件を Flow で監視する。 */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis DESC, id DESC
        LIMIT 1
        """
    )
    fun latestOneFlow(): Flow<LocationSample?>

    /** 全件を timeMillis 昇順・id 昇順で取得（時系列用途向け）。 */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis ASC, id ASC
        """
    )
    suspend fun findAll(): List<LocationSample>

    /**
     * 期間 [from, to) のレコードを timeMillis 昇順・id 昇順で取得する。
     * JST の 0:00 区切りなど、日付ベースの抽出に利用することを想定。
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
     * 期間 [from, to) のレコードを timeMillis 昇順で最大 softLimit 件取得する。
     * Worker による日次エクスポート処理での使用を想定。
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

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun countAll(): Long

    /**
     * 渡されたレコード群をまとめて削除する。
     * 通常はアップロード成功後のクリーンアップ用途で使用する。
     */
    @Delete
    suspend fun deleteAll(items: List<LocationSample>)
}

