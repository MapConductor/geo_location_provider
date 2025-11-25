package com.mapconductor.plugin.provider.geolocation.usecase

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.repository.LocationSampleSource
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample

/**
 * Factory to create BuildSelectedSlots for UI usage.
 *
 * - PickupScreen and other callers can use BuildSelectedSlots without knowing DB/DAO types.
 * - Actual data access is done via StorageService; Room/AppDatabase is not touched directly.
 */
object SelectorUseCases {

    fun buildSelectedSlots(context: Context): BuildSelectedSlots {
        val appContext = context.applicationContext

        // Wrap StorageService as a LocationSampleSource implementation
        val source = object : LocationSampleSource {
            override suspend fun findBetween(
                fromInclusive: Long,
                toExclusive: Long
            ): List<LocationSample> {
                // StorageService also uses [from, to) half-open interval and ascending order
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

