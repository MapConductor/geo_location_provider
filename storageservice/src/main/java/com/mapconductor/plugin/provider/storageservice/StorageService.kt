package com.mapconductor.plugin.provider.storageservice

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Room への直接アクセスを“ここ”に集約する薄いファサード。
 * - Service / ViewModel / UI からは AppDatabase/DAO を直接参照しない
 */
object StorageService {

    /**
     * 最新 N 件を Flow で監視する。
     * 履歴画面（HistoryViewModel）などから利用。
     */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> {
        val dao = AppDatabase.get(ctx).locationSampleDao()
        return dao.latestFlow(limit)
    }

    /**
     * 全 LocationSample を時系列昇順で取得。
     * 呼び出し側からは DB/DAO を意識させない。
     */
    suspend fun getAllLocations(ctx: Context): List<LocationSample> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.findAll()
        }

    /**
     * 指定期間 [fromMillis, toMillis) の LocationSample を取得。
     * ManualExportViewModel / DriveSettingsScreen などの日次処理から利用。
     */
    suspend fun getLocationsBetween(
        ctx: Context,
        fromMillis: Long,
        toMillis: Long
    ): List<LocationSample> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.findBetween(fromMillis, toMillis)
        }

    /**
     * LocationSample を 1 件挿入。
     * 既存どおり、簡易なトレースログも出す。
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

    /**
     * 渡された LocationSample 群をまとめて削除。
     * （今後 MidnightExportWorker から利用予定）
     */
    suspend fun deleteLocations(ctx: Context, items: List<LocationSample>) =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.deleteAll(items)
        }
}
