package com.mapconductor.plugin.provider.storageservice

import android.content.Context
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * Room への直接アクセスを“ここ”に集約する薄いファサード。
 * - Service / ViewModel / UI からは AppDatabase/DAO を直接参照しない
 * - 将来、独立モジュール化しても呼び口はそのまま
 */
object StorageService {

    /** 直近 N 件（降順） */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> {
        val dao = AppDatabase.get(ctx).locationSampleDao()
        return dao.latestFlow(limit)
    }

    /** 最後の1件（Flow） */
    fun latestOneFlow(ctx: Context): Flow<LocationSample?> {
        val dao = AppDatabase.get(ctx).locationSampleDao()
        return dao.latestOneFlow()
    }

    /** 1件挿入 */
    suspend fun insertLocation(ctx: Context, sample: LocationSample) {
        val dao = AppDatabase.get(ctx).locationSampleDao()
        dao.insert(sample)
    }
}
