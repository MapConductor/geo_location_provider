package com.mapconductor.plugin.provider.geolocation.repository

import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.algorithm.buildTargetsFromEnd
import com.mapconductor.plugin.provider.geolocation.algorithm.buildTargetsFromStart
import com.mapconductor.plugin.provider.geolocation.algorithm.directToSlots
import com.mapconductor.plugin.provider.geolocation.algorithm.effectiveLimit
import com.mapconductor.plugin.provider.geolocation.algorithm.snapToGrid
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SortOrder

/**
 * Implementation of extraction and gap-filling logic based on LocationSampleSource.
 * - When from > to, the condition is normalized in SelectorCondition.normalized().
 * - When intervalSec == null, this performs direct extraction (no grid).
 * - When intervalSec is specified, this performs grid snapping (+/- T/2 window, gaps are represented as sample == null).
 */
class SelectorRepository(
    private val source: LocationSampleSource
) {
    suspend fun select(condRaw: SelectorCondition): List<SelectedSlot> {
        val cond = condRaw.normalized()

        val from = cond.fromMillis
        val to   = cond.toMillis

        // Direct extraction (no grid).
        if (cond.intervalSec == null) {
            val asc = fetchAsc(from, to)       // Fetch records in ascending order.
            val filtered = filterByAccuracy(asc, cond.minAccuracy)
            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // For grid snapping. Assumes from/to/T are all non-null.
        val T = cond.intervalSec.coerceAtLeast(1L) * 1000L
        val W = T / 2L

        // If either from or to is null, grid cannot be built safely, so fall back to direct extraction.
        if (from == null || to == null) {
            val asc = fetchAsc(from, to)
            val filtered = filterByAccuracy(asc, cond.minAccuracy)
            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // Grid depends on SortOrder:
        // - NewestFirst : build from To (endInclusive) backwards.
        // - OldestFirst : build from From (startInclusive) forwards.
        val targets = when (cond.order) {
            SortOrder.NewestFirst -> buildTargetsFromEnd(from, to, T)
            SortOrder.OldestFirst -> buildTargetsFromStart(from, to, T)
        }

        // Fetch candidates at once: [from-W, to+W). Caller uses half-open [from, toExcl), so add +1ms.
        val fromQ = from - W
        val toQExcl = (to + W) + 1L
        val ascAll = source.findBetween(fromQ, toQExcl)

        val cand = filterByAccuracy(ascAll, cond.minAccuracy)

        // For snapping; assumes grid is in ascending order.
        var slots = snapToGrid(cand, targets, W)

        // Final ordering.
        when (cond.order) {
            SortOrder.OldestFirst -> {
                // targets are from -> to in ascending order; keep as-is.
            }
            SortOrder.NewestFirst -> {
                slots = slots.asReversed()
            }
        }

        // Final limit handling.
        val maxCount = effectiveLimit(cond.limit)
        return if (maxCount != null) slots.take(maxCount) else slots
    }

    // ----------------------
    // Internal helpers
    // ----------------------
    private suspend fun fetchAsc(from: Long?, to: Long?): List<LocationSample> {
        val f = from ?: Long.MIN_VALUE
        val tExcl = ((to ?: Long.MAX_VALUE - 1L) + 1L) // Upper bound of half-open interval.
        return source.findBetween(f, tExcl)
    }

    private fun filterByAccuracy(
        input: List<LocationSample>,
        maxAcc: Float?
    ): List<LocationSample> {
        maxAcc ?: return input
        return input.filter { it.accuracy <= maxAcc }
    }

    /**
     * Final ordering and limit for direct extraction.
     * - OldestFirst  : take from the head up to limit.
     * - NewestFirst  : take from the tail up to limit, then order newest -> oldest.
     */
    private fun limitAndOrderForDirect(
        asc: List<LocationSample>,
        cond: SelectorCondition
    ): List<LocationSample> {
        val maxCount = effectiveLimit(cond.limit)
        return when (cond.order) {
            SortOrder.OldestFirst -> {
                if (maxCount != null) asc.take(maxCount) else asc
            }
            SortOrder.NewestFirst -> {
                val src = if (maxCount != null) {
                    if (asc.size <= maxCount) asc else asc.takeLast(maxCount)
                } else asc
                src.asReversed()
            }
        }
    }
}

