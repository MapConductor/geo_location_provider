package com.mapconductor.plugin.provider.geolocation.usecase

import android.app.Application
import android.content.Context
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase

/**
 * dataselector のユースケースを、UI から簡単に取得するためのファクトリ。
 * - PickupScreen などは DB や DAO 型を知らずに BuildSelectedSlots を利用できる。
 * - 現時点では AppDatabase 依存はここに閉じ込める（次のステップでさらに整理する前提）。
 */
object SelectorUseCases {

    fun buildSelectedSlots(context: Context): BuildSelectedSlots {
        val app = context.applicationContext as Application
        val dao = AppDatabase.Companion.get(app).locationSampleDao()
        val repo = SelectorRepository(dao)
        return BuildSelectedSlots(repo)
    }
}