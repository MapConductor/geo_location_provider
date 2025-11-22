package com.mapconductor.plugin.provider.geolocation.algorithm

import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import kotlin.math.abs
import kotlin.math.max

/**
 * start 〜 end を含むグリッド時刻列を生成する。
 *
 * @param startInclusive 開始時刻（ミリ秒, inclusive）
 * @param endInclusive 終了時刻（ミリ秒, inclusive）
 * @param intervalMs グリッド間隔（ミリ秒, 0 以下なら空）
 */
fun buildTargetsInclusive(
    startInclusive: Long,
    endInclusive: Long,
    intervalMs: Long
): List<Long> {
    if (intervalMs <= 0L) return emptyList()
    if (endInclusive < startInclusive) return emptyList()

    val first = (startInclusive / intervalMs) * intervalMs
    val result = ArrayList<Long>(
        ((max(0L, endInclusive - startInclusive) / intervalMs) + 2).toInt()
    )
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
 * グリッド吸着ロジック。
 *
 * 各グリッド時刻 g について [g - halfWindowMs, g + halfWindowMs] に入る sample を探索し、
 * |Δt| が最小のものを 1 件だけ採用する。同差の場合は「より過去側」が優先される。
 *
 * @param records timeMillis 昇順の LocationSample 一覧
 * @param grid    グリッド時刻列（ミリ秒）
 * @param halfWindowMs 吸着窓の半幅（ミリ秒）
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
            // 同差は先勝ち（= より過去側を優先）
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
 * ダイレクト抽出（グリッド無し）の結果を SelectedSlot に変換する。
 * - idealMs = sample.timeMillis
 * - deltaMs = 0
 */
fun directToSlots(records: List<LocationSample>): List<SelectedSlot> =
    records.map { SelectedSlot(idealMs = it.timeMillis, sample = it, deltaMs = 0L) }

/**
 * limit 値の正規化。
 *
 * @return 1 以上ならその値、それ以外（null / 0 以下）の場合は null（= 無制限）
 */
fun effectiveLimit(maxCount: Int?): Int? =
    maxCount?.takeIf { it > 0 }

/**
 * グリッド取得時の「候補読み込み件数」のソフト上限を計算する。
 * - limit が指定されている場合は、その約 5 倍をベースとしつつ [1,000, 200,000] にクランプ
 * - limit が null の場合は、デフォルト 100 件相当をベースにする
 */
fun softLimitForGrid(limit: Int?): Int {
    val baseLimit = effectiveLimit(limit) ?: 100
    val base = (baseLimit * 5).coerceAtLeast(1_000)
    return base.coerceAtMost(200_000)
}

