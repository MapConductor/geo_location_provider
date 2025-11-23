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
 * LocationSampleSource を用いた抽出/補完の実装クラス。
 * - from > to は正規化（SelectorCondition.normalized() 側で実施）。
 * - intervalSec == null の場合はダイレクト抽出（グリッド無し）。
 * - intervalSec が指定されている場合はグリッド吸着（±T/2、欠測は sample=null）。
 */
class SelectorRepository(
    private val source: LocationSampleSource
) {
    suspend fun select(condRaw: SelectorCondition): List<SelectedSlot> {
        val cond = condRaw.normalized()

        val from = cond.fromMillis
        val to   = cond.toMillis

        // ダイレクト抽出（グリッド無し）
        if (cond.intervalSec == null) {
            val asc = fetchAsc(from, to)       // 取得自体は昇順
            val filtered = filterByAccuracy(asc, cond.minAccuracy)
            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // グリッド吸着用。from/to/T が揃っていることを前提。
        val T = cond.intervalSec.coerceAtLeast(1L) * 1000L
        val W = T / 2L

        // from/to のどちらかが null の場合、グリッドを安全に組めないのでダイレクトにフォールバック
        if (from == null || to == null) {
            val asc = fetchAsc(from, to)
            val filtered = filterByAccuracy(asc, cond.minAccuracy)
            val limited = limitAndOrderForDirect(filtered, cond)
            return directToSlots(limited)
        }

        // グリッドは SortOrder に応じて基準を変える。
        // - NewestFirst : To 基準 (endInclusive からさかのぼる)
        // - OldestFirst : From 基準 (startInclusive から積み上げる)
        val targets = when (cond.order) {
            SortOrder.NewestFirst -> buildTargetsFromEnd(from, to, T)
            SortOrder.OldestFirst -> buildTargetsFromStart(from, to, T)
        }

        // 候補を一括取得。[from-W, to+W)  ※ 取得側は半開区間 toExcl のため +1ms で inclusive
        val fromQ = from - W
        val toQExcl = (to + W) + 1L
        val ascAll = source.findBetween(fromQ, toQExcl)

        val cand = filterByAccuracy(ascAll, cond.minAccuracy)

        // 吸着用。grid は昇順を前提。
        var slots = snapToGrid(cand, targets, W)

        // 最終並び順。
        when (cond.order) {
            SortOrder.OldestFirst -> {
                // targets は from→to の昇順なので、そのまま。
            }
            SortOrder.NewestFirst -> {
                slots = slots.asReversed()
            }
        }

        // 最終件数制御。
        val maxCount = effectiveLimit(cond.limit)
        return if (maxCount != null) slots.take(maxCount) else slots
    }

    // ----------------------
    // 内部ヘルパー
    // ----------------------
    private suspend fun fetchAsc(from: Long?, to: Long?): List<LocationSample> {
        val f = from ?: Long.MIN_VALUE
        val tExcl = ((to ?: Long.MAX_VALUE - 1L) + 1L) // 半開区間の上端
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
     * ダイレクト抽出の最終並び・件数制御。
     * - OldestFirst  : そのまま頭から limit 件。
     * - NewestFirst  : 末尾側から limit 件を取り、順序は新しい→古いに並べ替える。
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

