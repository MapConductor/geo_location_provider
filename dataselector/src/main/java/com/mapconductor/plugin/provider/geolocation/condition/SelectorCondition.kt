package com.mapconductor.plugin.provider.geolocation.condition

import com.mapconductor.plugin.provider.storageservice.room.LocationSample

/**
 * Condition for data extraction.
 *
 * - fromMillis/toMillis can be set independently; null means unbounded.
 * - When intervalSec is null: direct extraction (no grid).
 * - When intervalSec is specified: grid snapping (+/- T/2 window, gaps are represented as sample == null).
 * - limit is effective only when >= 1. null/<=0 means "no limit".
 * - minAccuracy allows only samples with accuracy <= value; null means no accuracy filter.
 * - order is the final output order (OldestFirst/NewestFirst).
 */
data class SelectorCondition(
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val intervalSec: Long? = null,
    val limit: Int? = null,
    val minAccuracy: Float? = null,
    val order: SortOrder = SortOrder.OldestFirst
) {
    /** When both ends exist and from > to, swap them. */
    fun normalized(): SelectorCondition {
        val f = fromMillis
        val t = toMillis
        return if (f != null && t != null && f > t) copy(fromMillis = t, toMillis = f) else this
    }
}

/** Final output order. */
enum class SortOrder {
    OldestFirst, NewestFirst
}

/**
 * One row of grid-snapped or direct extraction result.
 * - idealMs: target (ideal) timestamp.
 * - sample : selected sample around idealMs (null when gap).
 * - deltaMs: sample.timeMillis - idealMs (when sample is not null).
 *
 * In direct extraction (intervalSec == null),
 *   idealMs = sample.timeMillis and deltaMs = 0.
 */
data class SelectedSlot(
    val idealMs: Long,
    val sample: LocationSample?,
    val deltaMs: Long?
)
