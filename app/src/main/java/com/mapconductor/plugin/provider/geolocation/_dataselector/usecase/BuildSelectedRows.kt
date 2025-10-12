package com.mapconductor.plugin.provider.geolocation._dataselector.usecase

import com.mapconductor.plugin.provider.geolocation._dataselector.prefs.SelectorPrefs
import com.mapconductor.plugin.provider.geolocation._dataselector.repository.SelectorRepository
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * UseCase：条件Flow × ベースFlow → 抽出済み行Flow を組み立てる
 */
class BuildSelectedRows(
    private val prefs: SelectorPrefs,
    private val repo: SelectorRepository
) {
    operator fun invoke(): Flow<List<LocationSample>> = repo.rows(prefs.condition)
}
