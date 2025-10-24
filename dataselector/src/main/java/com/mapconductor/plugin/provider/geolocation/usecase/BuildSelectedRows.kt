package com.mapconductor.plugin.provider.geolocation.usecase

import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository

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
