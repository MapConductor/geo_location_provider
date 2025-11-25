package com.mapconductor.plugin.provider.geolocation.repository

import com.mapconductor.plugin.provider.storageservice.room.LocationSample

/**
 * Abstraction for the source of LocationSample records.
 * - dataselector does not know about Room/DAO and depends only on this interface.
 */
interface LocationSampleSource {

    /**
     * Fetch LocationSample records in the half-open interval [fromInclusive, toExclusive) in ascending order.
     */
    suspend fun findBetween(
        fromInclusive: Long,
        toExclusive: Long
    ): List<LocationSample>
}
