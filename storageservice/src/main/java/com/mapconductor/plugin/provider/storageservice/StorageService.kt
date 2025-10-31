package com.mapconductor.plugin.provider.storageservice

import com.mapconductor.plugin.provider.storageservice.domain.LocationLog
import kotlinx.coroutines.flow.Flow

interface StorageService {
    suspend fun insertLocation(log: LocationLog)
    fun observeLocations(limit: Int = 100): Flow<List<LocationLog>>
    suspend fun queryLocationsBetween(fromMillis: Long, toMillis: Long): List<LocationLog>
}
