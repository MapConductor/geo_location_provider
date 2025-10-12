package com.mapconductor.plugin.provider.geolocation._dataselector.repository

import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * フィールド名に依存しないよう、時刻/精度の取得関数を注入するジェネリック実装。
 */
class SelectorRepository<T>(
    private val baseFlow: Flow<List<T>>,
    private val getMillis: (T) -> Long,
    private val getAccuracy: (T) -> Float?
) {
    fun rows(conditionFlow: Flow<SelectorCondition>): Flow<List<T>> =
        combine(baseFlow, conditionFlow) { base, cond ->
            var list = base

            // 1) 精度フィルタ
            cond.minAccuracyM?.let { acc ->
                list = list.filter { sample ->
                    val a = getAccuracy(sample)
                    a == null || a <= acc
                }
            }

            // 2) 期間/件数
            when (cond.mode) {
                SelectorCondition.Mode.ByPeriod -> {
                    val from = cond.fromMillis ?: Long.MIN_VALUE
                    val to   = cond.toMillis ?: Long.MAX_VALUE
                    val (lo, hi) = if (from <= to) from to to else to to from

                    // 2-1) 期間
                    list = list
                        .asSequence()
                        .filter { sample ->
                            val t = getMillis(sample)
                            t in lo..hi
                        }
                        .sortedByDescending { sample -> getMillis(sample) }
                        .toList()

                    // 2-2) 間引き（intervalMs）※指定があれば適用
                    cond.intervalMs?.takeIf { it > 0 }?.let { interval ->
                        list = thinByIntervalDescending(list, interval)
                    }
                }

                SelectorCondition.Mode.ByCount -> {
                    // 2-1) 降順ソート
                    list = list
                        .asSequence()
                        .sortedByDescending { sample -> getMillis(sample) }
                        .toList()

                    // 2-2) 間引き（intervalMs）※ByCountでも適用
                    cond.intervalMs?.takeIf { it > 0 }?.let { interval ->
                        list = thinByIntervalDescending(list, interval)
                    }

                    // 2-3) 件数でトリミング
                    val n = (cond.limit ?: 100).coerceAtLeast(1)
                    list = list.take(n)
                }
            }
            list
        }

    /**
     * すでに「降順（新しい→古い）」に並んでいる前提で、intervalMs 以上の間隔を空けて採用する。
     */
    private fun thinByIntervalDescending(list: List<T>, intervalMs: Long): List<T> {
        if (list.isEmpty()) return list
        val out = ArrayList<T>(list.size)
        var lastAccepted: Long? = null
        for (s in list) {
            val t = getMillis(s)
            if (lastAccepted == null || (lastAccepted - t) >= intervalMs) {
                out += s
                lastAccepted = t
            }
        }
        return out
    }
}
