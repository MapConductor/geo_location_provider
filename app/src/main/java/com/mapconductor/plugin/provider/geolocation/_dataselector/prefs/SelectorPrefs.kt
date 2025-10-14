package com.mapconductor.plugin.provider.geolocation._dataselector.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition.Mode
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.selectorDataStore by preferencesDataStore(name = "selector_prefs")

class SelectorPrefs(private val appContext: Context) {
    private object K {
        // 既存
        val MODE = stringPreferencesKey("mode")
        val FROM = longPreferencesKey("from")
        val TO = longPreferencesKey("to")
        val LIMIT = intPreferencesKey("limit")
        val MIN_ACC = floatPreferencesKey("min_acc")
        val INTERVAL_MS = longPreferencesKey("interval_ms")

        // 新規（任意）：保存されていなければ null でOK（後方互換）
        val FROM_HMS = stringPreferencesKey("from_hms")
        val TO_HMS = stringPreferencesKey("to_hms")
        val SORT_ORDER = stringPreferencesKey("sort_order") // NewestFirst / OldestFirst
    }

    /** 復元：新キーがあれば使い、無ければ旧キーのみで組み立てる（後方互換） */
    val condition: Flow<SelectorCondition> =
        appContext.selectorDataStore.data.map { p ->
            SelectorCondition(
                mode = p[K.MODE]?.let { runCatching { Mode.valueOf(it) }.getOrDefault(Mode.ByPeriod) } ?: Mode.ByPeriod,
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                limit = p[K.LIMIT],
                minAccuracyM = p[K.MIN_ACC],
                intervalMs = p[K.INTERVAL_MS],
                // 新規（任意）
                fromHms = p[K.FROM_HMS],
                toHms = p[K.TO_HMS],
                sortOrder = p[K.SORT_ORDER]
                    ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                    ?: SortOrder.NewestFirst
            )
        }

    /** 保存：null のものは削除。新旧キーの両方に書いておくと安全（秒が主・msは互換） */
    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        appContext.selectorDataStore.edit { p ->
            val curr = SelectorCondition(
                mode = p[K.MODE]?.let { runCatching { Mode.valueOf(it) }.getOrDefault(Mode.ByPeriod) } ?: Mode.ByPeriod,
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                limit = p[K.LIMIT],
                minAccuracyM = p[K.MIN_ACC],
                intervalMs = p[K.INTERVAL_MS],
                fromHms = p[K.FROM_HMS],
                toHms = p[K.TO_HMS],
                sortOrder = p[K.SORT_ORDER]
                    ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                    ?: SortOrder.NewestFirst
            )

            val next = block(curr)

            // 既存キー
            p[K.MODE] = next.mode.name
            next.fromMillis?.let { p[K.FROM] = it } ?: p.remove(K.FROM)
            next.toMillis?.let { p[K.TO] = it } ?: p.remove(K.TO)
            next.limit?.let { p[K.LIMIT] = it } ?: p.remove(K.LIMIT)
            next.minAccuracyM?.let { p[K.MIN_ACC] = it } ?: p.remove(K.MIN_ACC)

            // interval: 秒を主に保存（ms も互換のため併記）
            next.intervalMs?.let { p[K.INTERVAL_MS] = it } ?: p.remove(K.INTERVAL_MS)

            // 新規キー（任意）
            next.fromHms?.let { p[K.FROM_HMS] = it } ?: p.remove(K.FROM_HMS)
            next.toHms?.let { p[K.TO_HMS] = it } ?: p.remove(K.TO_HMS)
            p[K.SORT_ORDER] = next.sortOrder.name
        }
    }
}
