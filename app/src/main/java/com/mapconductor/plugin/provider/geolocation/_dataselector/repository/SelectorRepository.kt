package com.mapconductor.plugin.provider.geolocation._dataselector.repository

import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * DAO 直クエリへの最適化は後続StepでOK。
 * まずは「既存の全件Flow」からメモリフィルタで動作を維持します。
 */
class SelectorRepository(
    private val baseFlow: Flow<List<LocationSample>>
) {
    fun rows(conditionFlow: Flow<SelectorCondition>): Flow<List<LocationSample>> =
        combine(baseFlow, conditionFlow) { base, cond ->
            var list = base

            cond.minAccuracyM?.let { acc ->
                list = list.filter { it.accuracy == null || it.accuracy <= acc }
            }

            when (cond.mode) {
                SelectorCondition.Mode.ByPeriod -> {
                    val from = cond.fromMillis ?: Long.MIN_VALUE
                    val to = cond.toMillis ?: Long.MAX_VALUE
                    list = list
                        .asSequence()
                        .filter { it.createdAt in from..to }
                        .sortedByDescending { it.createdAt }
                        .toList()
                }
                SelectorCondition.Mode.ByCount -> {
                    val n = cond.limit ?: 100
                    list = list
                        .asSequence()
                        .sortedByDescending { it.createdAt }
                        .take(n)
                        .toList()
                }
            }
            list
        }
}
