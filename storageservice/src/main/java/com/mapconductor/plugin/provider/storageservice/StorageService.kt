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

    /** 直近 N 件（降順） */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> =
        AppDatabase.get(ctx).locationSampleDao().latestFlow(limit)

    /** 最後の1件（Flow） */
    fun latestOneFlow(ctx: Context): Flow<LocationSample?> {
        val dao = AppDatabase.get(ctx).locationSampleDao()
        return dao.latestOneFlow()
    }

    /** 1件挿入 */
//    suspend fun insertLocation(ctx: Context, sample: LocationSample) {
//        AppDatabase.get(ctx).locationSampleDao().insert(sample)
//    }
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
}
