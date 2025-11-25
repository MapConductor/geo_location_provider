package com.mapconductor.plugin.provider.geolocation.algorithm

import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import kotlin.math.abs
import kotlin.math.max

/**
 * Utility to build grid target timestamps.
 *
 * @param startInclusive start timestamp (milliseconds, inclusive)
 * @param endInclusive   end timestamp (milliseconds, inclusive)
 * @param intervalMs     grid interval (milliseconds, empty when <= 0)
 *
 * - For NewestFirst, build based on To (= endInclusive) using [buildTargetsFromEnd].
 * - For OldestFirst, build based on From (= startInclusive) using [buildTargetsFromStart].
 */
internal fun buildTargetsFromEnd(
    startInclusive: Long,
    endInclusive: Long,
    intervalMs: Long
): List<Long> {
    if (intervalMs <= 0L) return emptyList()
    if (endInclusive < startInclusive) return emptyList()

    val result = ArrayList<Long>()
    var g = endInclusive
    while (g >= startInclusive) {
        result.add(g)
        g -= intervalMs
    }
    // Currently collected in descending order; reverse to ascending.
    result.reverse()
    return result
}

internal fun buildTargetsFromStart(
    startInclusive: Long,
    endInclusive: Long,
    intervalMs: Long
): List<Long> {
    if (intervalMs <= 0L) return emptyList()
    if (endInclusive < startInclusive) return emptyList()

    val result = ArrayList<Long>()
    var g = startInclusive
    val end = endInclusive
    while (g <= end) {
        result.add(g)
        g += intervalMs
    }
    return result
}

/**
 * Grid snapping logic.
 *
 * For each grid time g, search samples in [g - halfWindowMs, g + halfWindowMs]
 * and select exactly one sample with the smallest absolute time difference; on ties, prefer the earlier sample.
 *
 * @param records list of LocationSample in ascending timeMillis order
 * @param grid    sorted list of grid timestamps (milliseconds, ascending)
 * @param halfWindowMs half-width of the snapping window (milliseconds)
 */
internal fun snapToGrid(
    records: List<LocationSample>,
    grid: List<Long>,
    halfWindowMs: Long
): List<SelectedSlot> {
    val out = ArrayList<SelectedSlot>(grid.size)
    var p = 0
    for (g in grid) {
        val left = g - halfWindowMs
        val right = g + halfWindowMs

        // Move pointer to the left edge.
        while (p < records.size && records[p].timeMillis < left) p++

        var bestIdx = -1
        var bestAbs = Long.MAX_VALUE
        var q = p
        while (q < records.size) {
            val t = records[q].timeMillis
            if (t > right) break
            val ad = abs(t - g)
            if (ad < bestAbs) {
                bestAbs = ad
                bestIdx = q
            }
            // On ties, earlier sample wins.
            q++
        }

        if (bestIdx >= 0) {
            val s = records[bestIdx]
            out.add(
                SelectedSlot(
                    idealMs = g,
                    sample = s,
                    deltaMs = s.timeMillis - g
                )
            )
        } else {
            out.add(SelectedSlot(idealMs = g, sample = null, deltaMs = null))
        }
    }
    return out
}

/**
 * Convert direct-extraction records (no grid) into SelectedSlot.
 * - idealMs = sample.timeMillis
 * - deltaMs = 0
 */
internal fun directToSlots(records: List<LocationSample>): List<SelectedSlot> =
    records.map { SelectedSlot(idealMs = it.timeMillis, sample = it, deltaMs = 0L) }

/**
 * Normalize limit value.
 *
 * @return value when >= 1, else null (unlimited).
 */
internal fun effectiveLimit(maxCount: Int?): Int? =
    maxCount?.takeIf { it > 0 }

/**
 * Compute soft upper bound of candidate rows for grid mode.
 * - When limit is specified, base = limit * 5 clamped to [1,000, 200,000].
 * - When limit is null, base is equivalent to default 100 rows.
 */
internal fun softLimitForGrid(limit: Int?): Int {
    val baseLimit = effectiveLimit(limit) ?: 100
    val base = (baseLimit * 5).coerceAtLeast(1_000)
    return base.coerceAtMost(200_000)
}

