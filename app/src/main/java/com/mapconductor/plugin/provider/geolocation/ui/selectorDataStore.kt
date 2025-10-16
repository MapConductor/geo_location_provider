package com.mapconductor.plugin.provider.geolocation.ui

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

// DataStore インスタンス
private val Context.selectorDataStore by preferencesDataStore(name = "selector_prefs")

class SelectorPrefs(private val appContext: Context) {

    private object K {
        // 既存・基本
        val FROM = longPreferencesKey("from")
        val TO = longPreferencesKey("to")
        val LIMIT = intPreferencesKey("limit")
        val MIN_ACC = floatPreferencesKey("min_acc")

        // interval は「秒」を正とする（新仕様）
        val INTERVAL_SEC = longPreferencesKey("interval_sec")

        // 後方互換（過去は ms 保存していた可能性）
        val LEGACY_INTERVAL_MS = longPreferencesKey("interval_ms")

        // 画面表示用の補助（任意・SelectorCondition には含めない）
        val FROM_HMS = stringPreferencesKey("from_hms")
        val TO_HMS = stringPreferencesKey("to_hms")

        // 並び順
        val SORT_ORDER = stringPreferencesKey("sort_order") // NewestFirst / OldestFirst
    }

    /** 条件の復元（後方互換：interval_ms→秒に変換して読む） */
    val condition: Flow<SelectorCondition> =
        appContext.selectorDataStore.data.map { p ->
            val order = p[K.SORT_ORDER]
                ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.NewestFirst

            val intervalSec: Long? = p[K.INTERVAL_SEC]
                ?: p[K.LEGACY_INTERVAL_MS]?.let { ms -> (ms / 1000L).coerceAtLeast(1L) }

            SelectorCondition(
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                intervalSec = intervalSec,
                limit = p[K.LIMIT],
                minAccuracy = p[K.MIN_ACC],
                order = order
            )
        }

    /** 画面補助：HH:mm:ss 文字列の保存値（任意） */
    val fromHms: Flow<String?> =
        appContext.selectorDataStore.data.map { it[K.FROM_HMS] }

    val toHms: Flow<String?> =
        appContext.selectorDataStore.data.map { it[K.TO_HMS] }

    /**
     * 条件の更新。
     * - null の項目は削除
     * - interval は「秒」で保存（旧 ms キーは削除）
     * - fromHms/toHms は任意で同時更新可（条件本体には含めない）
     */
    suspend fun update(
        block: (SelectorCondition) -> SelectorCondition,
        fromHms: String? = null,
        toHms: String? = null
    ) {
        appContext.selectorDataStore.edit { p ->
            // 現在値（後方互換あり）を読み出し
            val currentOrder = p[K.SORT_ORDER]
                ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.NewestFirst
            val currentIntervalSec: Long? = p[K.INTERVAL_SEC]
                ?: p[K.LEGACY_INTERVAL_MS]?.let { ms -> (ms / 1000L).coerceAtLeast(1L) }

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

            // interval（秒で保存）。旧 ms キーは削除。
            next.intervalSec?.let { p[K.INTERVAL_SEC] = it } ?: p.remove(K.INTERVAL_SEC)
            p.remove(K.LEGACY_INTERVAL_MS)

            // 並び順
            p[K.SORT_ORDER] = next.order.name

            // 画面補助（任意）
            fromHms?.let { if (it.isNotBlank()) p[K.FROM_HMS] = it else p.remove(K.FROM_HMS) }
            toHms?.let { if (it.isNotBlank()) p[K.TO_HMS] = it else p.remove(K.TO_HMS) }
        }
    }
}
