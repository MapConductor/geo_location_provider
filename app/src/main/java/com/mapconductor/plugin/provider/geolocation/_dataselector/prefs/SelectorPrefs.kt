package com.mapconductor.plugin.provider.geolocation._dataselector.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

// DataStore
private val Context.selectorDataStore by preferencesDataStore(name = "selector_prefs")

class SelectorPrefs(private val appContext: Context) {

    private object K {
        // 基本
        val FROM = longPreferencesKey("from")
        val TO = longPreferencesKey("to")
        val LIMIT = intPreferencesKey("limit")
        val MIN_ACC = floatPreferencesKey("min_acc")

        // interval：新仕様は「秒」を保存
        val INTERVAL_SEC = longPreferencesKey("interval_sec")

        // 後方互換（過去に ms を保存していた可能性）
        val LEGACY_INTERVAL_MS = longPreferencesKey("interval_ms")

        // 画面補助（任意：条件本体には含めない）
        val FROM_HMS = stringPreferencesKey("from_hms")
        val TO_HMS = stringPreferencesKey("to_hms")

        // 並び順
        val SORT_ORDER = stringPreferencesKey("sort_order") // NewestFirst / OldestFirst
    }

    /** 条件の復元（LEGACY: interval_ms を秒に変換して読み込み） */
    val condition: Flow<SelectorCondition> =
        appContext.selectorDataStore.data.map { p ->
            val order = p[K.SORT_ORDER]
                ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.NewestFirst

            // intervalSec: 負値をガード、legacy(ms)→sec 変換
            val intervalSecFromStore = p[K.INTERVAL_SEC]
            val intervalSecFromLegacy = p[K.LEGACY_INTERVAL_MS]?.let { ms ->
                max(0L, ms / 1000L).takeIf { it > 0L }
            }
            val intervalSec: Long? = (intervalSecFromStore ?: intervalSecFromLegacy)?.let { max(0L, it) }?.takeIf { it > 0L }

            SelectorCondition(
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                intervalSec = intervalSec,
                limit = p[K.LIMIT],
                minAccuracy = p[K.MIN_ACC],
                order = order
            )
        }

    /** 画面補助（任意） */
    val fromHms: Flow<String?> =
        appContext.selectorDataStore.data.map { it[K.FROM_HMS] }
    val toHms: Flow<String?> =
        appContext.selectorDataStore.data.map { it[K.TO_HMS] }

    /**
     * 条件の更新。
     * - null の項目は削除
     * - interval は「秒」で保存（旧 ms キーはクリア）
     * - fromHms/toHms は任意の補助値（条件本体とは独立）
     */
    suspend fun update(
        block: (SelectorCondition) -> SelectorCondition,
        fromHms: String? = null,
        toHms: String? = null
    ) {
        appContext.selectorDataStore.edit { p ->
            // 現在値を復元（後方互換あり）
            val currentOrder = p[K.SORT_ORDER]
                ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.NewestFirst
            val currentIntervalSec: Long? =
                p[K.INTERVAL_SEC]
                    ?: p[K.LEGACY_INTERVAL_MS]?.let { ms -> max(0L, ms / 1000L).takeIf { it > 0L } }

            val curr = SelectorCondition(
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                intervalSec = currentIntervalSec,
                limit = p[K.LIMIT],
                minAccuracy = p[K.MIN_ACC],
                order = currentOrder
            )

            val next = block(curr)

            // 基本キー
            next.fromMillis?.let { p[K.FROM] = it } ?: p.remove(K.FROM)
            next.toMillis?.let { p[K.TO] = it } ?: p.remove(K.TO)
            next.limit?.let { p[K.LIMIT] = it } ?: p.remove(K.LIMIT)
            next.minAccuracy?.let { p[K.MIN_ACC] = it } ?: p.remove(K.MIN_ACC)

            // interval（秒で保存）。負値ガード。旧 ms キーは削除。
            val normalizedInterval = next.intervalSec?.let { max(0L, it) }?.takeIf { it > 0L }
            normalizedInterval?.let { p[K.INTERVAL_SEC] = it } ?: p.remove(K.INTERVAL_SEC)
            p.remove(K.LEGACY_INTERVAL_MS)

            // 並び順
            p[K.SORT_ORDER] = next.order.name

            // 補助値
            fromHms?.let { if (it.isNotBlank()) p[K.FROM_HMS] = it else p.remove(K.FROM_HMS) }
            toHms?.let { if (it.isNotBlank()) p[K.TO_HMS] = it else p.remove(K.TO_HMS) }
        }
    }

    // ===== 便利ヘルパ（任意） =====

    suspend fun setRange(fromMillis: Long?, toMillis: Long?) {
        update({ it.copy(fromMillis = fromMillis, toMillis = toMillis) })
    }

    suspend fun setLimit(limit: Int?) {
        update({ it.copy(limit = limit) })
    }

    suspend fun setIntervalSec(intervalSec: Long?) {
        update({ it.copy(intervalSec = intervalSec?.let { max(0L, it) }?.takeIf { it > 0L }) })
    }

    suspend fun setSortOrder(order: SortOrder) {
        update({ it.copy(order = order) })
    }

    suspend fun clearAll() {
        appContext.selectorDataStore.edit { it.clear() }
    }
}
