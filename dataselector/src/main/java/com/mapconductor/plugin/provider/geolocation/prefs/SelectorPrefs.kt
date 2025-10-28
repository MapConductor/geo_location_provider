package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

// Single DataStore instance bound to the application Context
private val Context.selectorDataStore by preferencesDataStore(name = "selector_prefs")

/**
 * DataStore facade for saving/restoring pickup/selection conditions.
 * - All units follow UI/domain conventions: milliseconds for from/to, seconds for interval.
 * - `limit` is clamped to [1, 20000].
 * - `intervalSec` must be > 0 when present; non-positive values are cleared.
 */
class SelectorPrefs(private val appContext: Context) {

    private object K {
        // Base filters
        val FROM = longPreferencesKey("from")
        val TO = longPreferencesKey("to")
        val LIMIT = intPreferencesKey("limit")
        val MIN_ACC = floatPreferencesKey("min_acc")
        // Interval: store as seconds; legacy ms key retained for migration
        val INTERVAL_SEC = longPreferencesKey("interval_sec")
        val LEGACY_INTERVAL_MS = longPreferencesKey("interval_ms")
        // Sort
        val ORDER = stringPreferencesKey("order")
    }

    private fun parseOrderOrDefault(raw: String?): SortOrder {
        val parsed = raw?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
        return parsed ?: SortOrder.entries.first()
    }

    /** Observe the current condition as a single flow. */
    val conditionFlow: Flow<SelectorCondition> =
        appContext.selectorDataStore.data.map { p ->
            // Migrate legacy interval if needed (ms -> sec, rounded down)
            val legacyMs = p[K.LEGACY_INTERVAL_MS]
            val intervalSec = p[K.INTERVAL_SEC] ?: legacyMs?.let { ms ->
                (ms / 1000L).takeIf { it > 0L }
            }

            val order = parseOrderOrDefault(p[K.ORDER])

            SelectorCondition(
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                intervalSec = intervalSec,
                limit = p[K.LIMIT],
                minAccuracy = p[K.MIN_ACC],
                order = order
            )
        }

    /** Update condition atomically via transformation block. */
    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        appContext.selectorDataStore.edit { p ->
            // Read current (apply migration the same way as in flow)
            val legacyMs = p[K.LEGACY_INTERVAL_MS]
            val currentIntervalSec =
                p[K.INTERVAL_SEC] ?: legacyMs?.let { it / 1000L }?.takeIf { it > 0L }
            val currentOrder = parseOrderOrDefault(p[K.ORDER])

            val curr = SelectorCondition(
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                intervalSec = currentIntervalSec,
                limit = p[K.LIMIT],
                minAccuracy = p[K.MIN_ACC],
                order = currentOrder
            )

            val next = block(curr)

            // Base keys
            next.fromMillis?.let { p[K.FROM] = it } ?: p.remove(K.FROM)
            next.toMillis?.let { p[K.TO] = it } ?: p.remove(K.TO)
            next.limit?.let { p[K.LIMIT] = it.coerceIn(1, 20_000) } ?: p.remove(K.LIMIT)
            next.minAccuracy?.let { p[K.MIN_ACC] = it } ?: p.remove(K.MIN_ACC)

            // Interval (seconds)
            next.intervalSec?.let { sec ->
                if (sec > 0) p[K.INTERVAL_SEC] = sec else p.remove(K.INTERVAL_SEC)
            } ?: p.remove(K.INTERVAL_SEC)
            // Legacy key always cleared after any write
            p.remove(K.LEGACY_INTERVAL_MS)

            // Order
            p[K.ORDER] = next.order.name
        }
    }

    // --- Fine-grained setters for UI ---

    suspend fun setFromTo(fromMillis: Long?, toMillis: Long?) {
        update { it.copy(fromMillis = fromMillis, toMillis = toMillis) }
    }

    suspend fun setLimit(limit: Int?) {
        update { it.copy(limit = limit?.coerceIn(1, 20_000)) }
    }

    suspend fun setMinAccuracy(minAcc: Float?) {
        update { it.copy(minAccuracy = minAcc) }
    }

    suspend fun setIntervalSec(intervalSec: Long?) {
        update { it.copy(intervalSec = intervalSec?.let { max(0L, it) }?.takeIf { it > 0L }) }
    }

    suspend fun setSortOrder(order: SortOrder) {
        update { it.copy(order = order) }
    }

    suspend fun clearAll() {
        appContext.selectorDataStore.edit { it.clear() }
    }
}
