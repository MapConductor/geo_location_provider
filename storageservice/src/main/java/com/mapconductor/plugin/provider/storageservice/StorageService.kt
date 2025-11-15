package com.mapconductor.plugin.provider.storageservice

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.storageservice.room.ExportedDay
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Room への直接アクセスを“ここ”に集約する薄いファサード。
 * - Service / ViewModel / UI からは AppDatabase/DAO を直接参照しない
 * - LocationSample / ExportedDay の両方をここでラップする
 */
object StorageService {

    /** 最新 N 件を Flow で監視する（履歴画面用）。 */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> =
        AppDatabase.get(ctx).locationSampleDao().latestFlow(limit)

    /** 全 LocationSample を時系列で取得（小規模利用前提）。 */
    suspend fun getAllLocations(ctx: Context): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao().findAll()
        }

    /**
     * 指定期間 [from, to) の LocationSample を昇順で取得。
     * - softLimit は安全のための上限（MidnightExport などで利用）
     */
    suspend fun getLocationsBetween(
        ctx: Context,
        from: Long,
        to: Long,
        softLimit: Int = 1_000_000
    ): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao()
                .getInRangeAscOnce(from = from, to = to, softLimit = softLimit)
        }

    /**
     * LocationSample を 1 件挿入。
     * - 既存どおり前後の件数と provider / timeMillis をログ出力する。
     */
    suspend fun insertLocation(ctx: Context, sample: LocationSample): Long =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            val before = dao.countAll()
            Log.d("DB/TRACE", "count-before=$before provider=${sample.provider} t=${sample.timeMillis}")

            val rowId = dao.insert(sample)

            val after = dao.countAll()
            Log.d("DB/TRACE", "count-after=$after rowId=$rowId")

            rowId
        }

    /** 渡された LocationSample 群をまとめて削除。 */
    suspend fun deleteLocations(ctx: Context, items: List<LocationSample>) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.deleteAll(items)
        }

    // ===== ExportedDay 系 =====

    /** 指定 epochDay の ExportedDay レコードを ensure（なければ挿入）。 */
    suspend fun ensureExportedDay(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            val dayDao = AppDatabase.get(ctx).exportedDayDao()
            dayDao.ensure(ExportedDay(epochDay = epochDay))
        }

    /** 未アップロード日のうち最古のものを取得。 */
    suspend fun oldestNotUploadedDay(ctx: Context): ExportedDay? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().oldestNotUploaded()
        }

    /** 指定日を「ローカルZIP生成済み」としてマーク。 */
    suspend fun markExportedLocal(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markExportedLocal(epochDay)
        }

    /** 指定日を「アップロード済み」としてマーク。 */
    suspend fun markUploaded(ctx: Context, epochDay: Long, fileId: String?) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markUploaded(epochDay, fileId)
        }

    /** 指定日にエラーメッセージを記録。 */
    suspend fun markExportError(ctx: Context, epochDay: Long, msg: String) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markError(epochDay, msg)
        }
}
