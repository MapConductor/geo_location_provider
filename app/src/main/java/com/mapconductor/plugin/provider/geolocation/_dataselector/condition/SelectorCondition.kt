package com.mapconductor.plugin.provider.geolocation._dataselector.condition

/** 表示順（結果の並び） */
enum class SortOrder { NewestFirst, OldestFirst }

/**
 * 抽出条件：
 *  - 期間（fromMillis < toMillis は UI層で正規化済み前提）
 *  - 並び順
 *  - 件数（上限；Hit＜Limit の場合は全件＝自然に Hit 件）
 *  - 間隔（秒）：null/<=0 で「間隔なし」。>0 で「ターゲット最近傍（同距離は古い方）」を適用
 */
data class SelectorCondition(
    val mode: Mode = Mode.ByPeriod,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val limit: Int? = null,
    val minAccuracyM: Float? = null,
    val intervalMs: Long? = null,
    val fromHms: String? = null,    // 記録用（解析には未使用）
    val toHms: String? = null,      // 記録用（解析には未使用）
    val sortOrder: SortOrder = SortOrder.NewestFirst
) {
    enum class Mode { ByPeriod, ByCount }
}

/** HMS 正規化（全角コロン→半角など）。必要ならUI側で利用してください。 */
fun normalizeHmsOrNull(text: String?): String? {
    val t = text?.trim().orEmpty().replace('：', ':')
    return if (t.isEmpty()) null else t
}
