package com.mapconductor.plugin.provider.geolocation.condition

import com.mapconductor.plugin.provider.geolocation.room.LocationSample

/**
 * データ抽出の条件。
 *
 * - fromMillis/toMillis はどちらか一方だけでも可（null は無制限）
 * - intervalSec が null の場合は「ダイレクト抽出」（グリッド無し）
 * - intervalSec が指定された場合は「グリッド吸着」（±T/2 窓、欠測は sample=null）
 * - limit は 1 以上で有効。null/<=0 は「無制限」
 * - minAccuracy は「この値以下の精度のみ許可」（null で無制限）
 * - order は「最終出力の並び」(OldestFirst/NewestFirst)
 */
data class SelectorCondition(
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val intervalSec: Long? = null,
    val limit: Int? = null,
    val minAccuracy: Float? = null,
    val order: SortOrder = SortOrder.OldestFirst
) {
    /** 両端があり、かつ from > to の場合は入れ替える */
    fun normalized(): SelectorCondition {
        val f = fromMillis
        val t = toMillis
        return if (f != null && t != null && f > t) copy(fromMillis = t, toMillis = f) else this
    }
}

/** 最終出力の並び順 */
enum class SortOrder {
    OldestFirst, NewestFirst
}

/**
 * グリッド吸着またはダイレクト抽出の結果 1 行を表す。
 * - idealMs: ターゲット（理想）時刻
 * - sample : 近傍で採用されたサンプル（無ければ null）
 * - deltaMs: sample.createdAt - idealMs（sample がある時のみ）
 *
 * intervalSec が null のダイレクト抽出では、
 *   idealMs = sample.createdAt, deltaMs = 0 として返す。
 */
data class SelectedSlot(
    val idealMs: Long,
    val sample: LocationSample?,
    val deltaMs: Long?
)
