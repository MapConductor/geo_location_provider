package com.mapconductor.plugin.provider.geolocation._dataselector.usecase

import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSampleDao
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 画面非依存の抽出ユースケース。
 * - 間隔なし：DAO の ORDER/LIMIT を利用（軽量）
 * - 間隔あり：ASC 一括取得 → ターゲット毎に「最近傍（同距離は古い方）」
 *   窓は非重複：
 *     k=0      : [τ0, τ0+Δ/2)
 *     0<k<M    : [τk−Δ/2, τk+Δ/2)
 *     k=M(To)  : [τM−Δ/2, τM]  （末尾のみ右端含む）
 */
class BuildSelectRows(
    private val dao: LocationSampleDao
) {
    /** LocationSample の時刻取り出し。プロジェクトの実フィールド名に合わせてここだけ変更してください。 */
    private fun ts(s: LocationSample): Long = (s.createdAt / 1000L)

    operator fun invoke(cond: SelectorCondition): Flow<List<LocationSample>> {
        val from = cond.fromMillis
        val to = cond.toMillis

        // 保険：UI層で正規化済みの想定だが、from>=to なら空
        if (from != null && to != null && from >= to) {
            return flow { emit(emptyList<LocationSample>()) }.flowOn(Dispatchers.IO)
        }

        val intervalSec: Int? =
            cond.intervalMs?.let { if (it > 0) (it / 1000L).toInt().coerceAtLeast(1) else null }

        // ▼ limit の既定値（nullや0以下は既定適用）
        val effectiveLimit = max(1, cond.limit ?: 100)

        // ---- 間隔なし：DAO のストレート抽出（Flow）----
        if (intervalSec == null) {
            return when (cond.sortOrder) {
                SortOrder.NewestFirst -> dao.getInRangeNewestFirst(from, to, effectiveLimit)
                SortOrder.OldestFirst -> dao.getInRangeOldestFirst(from, to, effectiveLimit)
            }
        }

        // ---- 間隔あり：メモリ最近傍（suspend→Flow）----
        return flow {
            val asc = dao.getInRangeAscOnce(
                from = from,
                to = to,
                softLimit = softLimitOf(effectiveLimit)
            )
            if (asc.isEmpty()) {
                emit(emptyList())
                return@flow
            }

            // from/to 未指定時は実データで補完（半開区間 [start, endExclusive) 前提）
            val start = from ?: ts(asc.first())
            val endExclusive = to ?: (ts(asc.last()) + 1)

            val intervalMs = intervalSec * 1000L
            val targets = buildTargetsInclusive(start, endExclusive, intervalMs)

            val picked = chooseNearestPerTarget(
                samplesAsc = asc,
                targets = targets,
                intervalMs = intervalMs,
                rangeStart = start,
                rangeEndExclusive = endExclusive
            )

            val ordered = when (cond.sortOrder) {
                SortOrder.NewestFirst -> picked.sortedByDescending { ts(it) }
                SortOrder.OldestFirst -> picked.sortedBy { ts(it) }
            }

            val out = if (ordered.size > effectiveLimit) {
                ordered.take(effectiveLimit)
            } else ordered

            emit(out)
        }.flowOn(Dispatchers.Default)
    }

    /* =================== 内部ユーティリティ =================== */

    private fun softLimitOf(limit: Int): Int {
        // ターゲット個数 ≒ 期間 / Δ + 1。上限は安全側に。
        val base = if (limit <= 0) 10_000 else limit * 5
        return min(200_000, max(1000, base))
    }

    /** From..To(Exclusive) を Δ で刻み、末尾 To を「含む」ターゲット列を生成 */
    private fun buildTargetsInclusive(start: Long, endExclusive: Long, intervalMs: Long): List<Long> {
        if (intervalMs <= 0L) return emptyList()
        val list = ArrayList<Long>(64)
        var t = start
        while (t < endExclusive) {
            list.add(t)
            t += intervalMs
        }
        if (list.isEmpty() || list.last() != endExclusive) list.add(endExclusive)
        return list
    }

    /**
     * 最近傍選定（同距離は古い方）＋ 自ウィンドウ外は参照不可。
     * 窓：
     *  k=0      : [τ0, τ0+Δ/2)
     *  0<k<M    : [τk−Δ/2, τk+Δ/2)
     *  k=M(To)  : [τM−Δ/2, τM]  （末尾のみ右端含む）
     */
    private fun chooseNearestPerTarget(
        samplesAsc: List<LocationSample>,
        targets: List<Long>,
        intervalMs: Long,
        rangeStart: Long,
        rangeEndExclusive: Long
    ): List<LocationSample> {
        if (targets.isEmpty()) return emptyList()
        val out = ArrayList<LocationSample>(targets.size)

        var idx = 0
        val n = samplesAsc.size

        for (k in targets.indices) {
            val tau = targets[k]

            val left = if (k == 0) tau else tau - intervalMs / 2
            val rightInclusive = (k == targets.lastIndex)
            val right = if (rightInclusive) tau else tau + intervalMs / 2

            val winStart = max(left, rangeStart)
            val winEndExclusive = min(if (rightInclusive) right + 1 else right, rangeEndExclusive)
            if (winStart >= winEndExclusive) continue

            // winStart まで前進
            while (idx < n && ts(samplesAsc[idx]) < winStart) idx++
            if (idx >= n) break

            var best: LocationSample? = null
            var bestDist = Long.MAX_VALUE

            var j = idx
            while (j < n) {
                val t = ts(samplesAsc[j])
                if (t >= winEndExclusive) break
                val d = abs(t - tau)
                if (d < bestDist) {
                    bestDist = d
                    best = samplesAsc[j]
                } else if (d == bestDist) {
                    // 同距離は古い方：ASC走査の“先勝ち”を維持
                }
                j++
            }

            best?.let { out.add(it) }
            idx = j
        }
        return out
    }
}
