package com.mapconductor.plugin.provider.geolocation.usecase

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.repository.LocationSampleSource
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample

/**
 * dataselector のユースケースを、UI から簡単に取得するためのファクトリ。
 * - PickupScreen などは DB や DAO 型を知らずに BuildSelectedSlots を利用できる。
 * - 実際のデータ取得は StorageService 経由で行い、Room/AppDatabase には触れない。
 */
object SelectorUseCases {

    fun buildSelectedSlots(context: Context): BuildSelectedSlots {
        val appContext = context.applicationContext

        // StorageService をラップして LocationSampleSource を実装
        val source = object : LocationSampleSource {
            override suspend fun findBetween(
                fromInclusive: Long,
                toExclusive: Long
            ): List<LocationSample> {
                // StorageService 側も [from, to) の半開区間・昇順返却である前提
                return StorageService.getLocationsBetween(
                    ctx = appContext,
                    from = fromInclusive,
                    to = toExclusive
                )
            }
        }

        val repo = SelectorRepository(source)
        return BuildSelectedSlots(repo)
    }
}
