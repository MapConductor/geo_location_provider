package com.mapconductor.plugin.provider.geolocation._dataselector.repository

import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.room.LocationSampleDao
import com.mapconductor.plugin.provider.geolocation._dataselector.algorithm.*
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder

/**
 * LocationSampleDao を用いた抽出/補完の実装。
 * - 今日限定のクリップは行わない（from/to はそのまま使う。null は無制限）
 * - from > to は正規化（入れ替え）
 * - intervalSec が null → ダイレクト抽出（グリッド無し）
 * - intervalSec が指定 → グリッド吸着（±T/2、欠測は sample=null）
 */
class SelectorRepository(
    private val dao: LocationSampleDao
) {
    suspend fun select(condRaw: SelectorCondition): List<SelectedSlot> {
        val cond = condRaw.normalized()

        val from = cond.fromMillis
        val to   = cond.toMillis

        // ダイレクト抽出（グリッド無し）
        if (cond.intervalSec == null) {
            val asc = fetchAsc(from, to)       // DAO は昇順返却
            val filtered = filterByAccuracy(asc, cond.minAccuracy)

            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // グリッド吸着（from/to/T が揃っていることを前提）
        val T = cond.intervalSec.coerceAtLeast(1L) * 1000L
        val W = T / 2L

        // from/to のどちらかが null の場合、グリッドを安全に組めない → ダイレクトへフォールバック
        if (from == null || to == null) {
            val asc = fetchAsc(from, to)
            val filtered = filterByAccuracy(asc, cond.minAccuracy)
            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // グリッド列（両端含む）
        val targets = buildTargetsInclusive(from, to, T)

        // 候補の一括取得： [from-W, to+W)  ※ DAO は半開区間 toExcl のため +1ms で inclusive
        val fromQ = from - W
        val toQExcl = (to + W) + 1L

        val ascAll = dao.findBetween(fromQ, toQExcl)

        // 精度で前処理
        val cand = filterByAccuracy(ascAll, cond.minAccuracy)

        // 吸着
        var slots = snapToGrid(cand, targets, W)

        // 最終順序
        when (cond.order) {
            SortOrder.OldestFirst -> {} // 既に昇順（targets 自体が from→to）
            SortOrder.NewestFirst -> slots = slots.asReversed()
        }

        // 最終件数制御（欠測行も件数にカウント）
        val maxCount = effectiveLimit(cond.limit)
        return if (maxCount != null) slots.take(maxCount) else slots
    }

    // ----------------------
    // 内部ヘルパ
    // ----------------------
    private suspend fun fetchAsc(from: Long?, to: Long?): List<LocationSample> {
        val f = from ?: Long.MIN_VALUE
        val tExcl = ((to ?: Long.MAX_VALUE - 1L) + 1L) // 半開区間の上端
        return dao.findBetween(f, tExcl)
    }

    private fun filterByAccuracy(
        input: List<LocationSample>,
        maxAcc: Float?
    ): List<LocationSample> {
        maxAcc ?: return input
        return input.filter { it.accuracy <= maxAcc }
    }

    /**
     * ダイレクト抽出の最終順序・件数制御。
     * - DAO は昇順返却（OldestFirst）。NewestFirst の場合は末尾から取る。
     * - limit は最終出力件数（null/<=0 なら無制限）
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
