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

    /** 最新 N 件（Flow）。同一時刻がある場合でも順序が安定するよう id を二次キーに使用 */
    @Query("""
    SELECT * FROM location_samples
    ORDER BY
      timeMillis DESC,
      CASE WHEN provider = 'gps' THEN 0 ELSE 1 END,  -- ★ 同一時刻ならGPSを先
      id DESC                                        -- ★ 二次キーで安定化
    LIMIT :limit
""")
    fun latestFlow(limit: Int): Flow<List<LocationSample>>

    /** 最後の1件（Flow） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis DESC, id DESC
        LIMIT 1
        """
    )
    fun latestOneFlow(): Flow<LocationSample?>

    /** 全件（昇順；GeoJSON など時系列用途, suspend） */
    @Query(
        """
        SELECT * FROM location_samples
        ORDER BY timeMillis ASC, id ASC
        """
    )
    suspend fun findAll(): List<LocationSample>


    /**
     * 期間抽出（[from, to) ; createdAt は epoch millis）
     * JST の 0:00 切りで使用（昇順）
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
     * 指定期間[from, to) のレコードを timeMillis 昇順で最大 softLimit 件取得。
     * Worker の日次エクスポートで使用。
     *
     * NOTE:
     *  - エンティティに tableName を付けている場合は、FROM のテーブル名を実名に合わせてください。
     *    例) @Entity(tableName = "location_samples") → FROM location_samples
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
     * 渡したレコードをまとめて削除。
     * （アップロード成功後のクリーンアップで使用）
     */
    @Delete
    suspend fun deleteAll(items: List<LocationSample>)
}
