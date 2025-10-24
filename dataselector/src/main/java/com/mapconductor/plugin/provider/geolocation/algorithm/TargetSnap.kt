package com.mapconductor.plugin.provider.geolocation.algorithm

import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import kotlin.math.abs
import kotlin.math.max

/** endInclusive を必ず含むグリッド列を生成（Δ=intervalMs）。start > end は空。 */
fun buildTargetsInclusive(
    startInclusive: Long,
    endInclusive: Long,
    intervalMs: Long
): List<Long> {
    if (intervalMs <= 0L) return emptyList()
    if (endInclusive < startInclusive) return emptyList()

    val first = ((startInclusive) / intervalMs) * intervalMs
    val result = ArrayList<Long>( ((max(0L, endInclusive - startInclusive) / intervalMs) + 2).toInt() )
    var g = first
    // g が start 未満の場合は繰り上げ
    while (g < startInclusive) g += intervalMs
    while (g <= endInclusive) {
        result.add(g)
        g += intervalMs
    }
    // 端が合っていなければ endInclusive を追加
    if (result.isEmpty() || result.last() != endInclusive) {
        result.add(endInclusive)
    }
    return result
}

/**
 * グリッド吸着：各 g について [g-W, g+W] に入る createdAt を探索し、|Δ| 最小 1 件を採用。
 * 同差は「過去側」優先（昇順走査の先勝ち）。
 * records は createdAt 昇順を想定。
 */
fun snapToGrid(
    records: List<LocationSample>,
    grid: List<Long>,
    halfWindowMs: Long
): List<SelectedSlot> {
    val out = ArrayList<SelectedSlot>(grid.size)
    var p = 0
    for (g in grid) {
        val left = g - halfWindowMs
        val right = g + halfWindowMs

        // 左端までポインタを進める
        while (p < records.size && records[p].createdAt < left) p++

        var bestIdx = -1
        var bestAbs = Long.MAX_VALUE
        var q = p
        while (q < records.size) {
            val t = records[q].createdAt
            if (t > right) break
            val ad = abs(t - g)
            if (ad < bestAbs) {
                bestAbs = ad
                bestIdx = q
            }
            // 同差は先勝ち（= 過去側）
            q++
        }

        if (bestIdx >= 0) {
            val s = records[bestIdx]
            out.add(SelectedSlot(idealMs = g, sample = s, deltaMs = s.createdAt - g))
        } else {
            out.add(SelectedSlot(idealMs = g, sample = null, deltaMs = null))
        }
    }
    return out
}

/** ダイレクト抽出（グリッド無し） → SelectedSlot に変換（ideal=createdAt, delta=0）。 */
fun directToSlots(records: List<LocationSample>): List<SelectedSlot> =
    records.map { SelectedSlot(idealMs = it.createdAt, sample = it, deltaMs = 0L) }

/** デフォルト100。指定があれば 1 以上のみ有効。 */
fun effectiveLimit(maxCount: Int?): Int? =
    maxCount?.takeIf { it > 0 } ?: 100

/**
 * 候補読み込みのソフト上限。
 * グリッド時は limit*5 を基準に 1000〜200,000 の範囲へクリップ。
 * ダイレクト時は null（=制御しない。DAO に LIMIT が無い場合に備え）
 */
fun softLimitForGrid(limit: Int?): Int {
    val base = ((effectiveLimit(limit) ?: 100) * 5).coerceAtLeast(1000)
    return base.coerceAtMost(200_000)
}
