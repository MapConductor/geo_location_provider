package com.mapconductor.plugin.provider.geolocation.usecase

import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository

/**
 * Screen-facing use case.
 * Builds a list of SelectedSlot including gaps, based on the given condition.
 */
class BuildSelectedSlots(
    private val repo: SelectorRepository
) {
    suspend operator fun invoke(cond: SelectorCondition): List<SelectedSlot> =
        repo.select(cond)
}

