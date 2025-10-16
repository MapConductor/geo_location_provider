package com.mapconductor.plugin.provider.geolocation._dataselector.usecase

import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation._dataselector.repository.SelectorRepository

/**
 * 画面用ユースケース：
 * 条件に基づき、欠測補完を含む SelectedSlot 一覧を構築。
 */
class BuildSelectedSlots(
    private val repo: SelectorRepository
) {
    suspend operator fun invoke(cond: SelectorCondition): List<SelectedSlot> =
        repo.select(cond)
}
