package com.mapconductor.plugin.provider.geolocation._dataselector.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition.Mode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.selectorDataStore by preferencesDataStore(name = "selector_prefs")

class SelectorPrefs(private val appContext: Context) {
    private object K {
        val MODE = stringPreferencesKey("mode")
        val FROM = longPreferencesKey("from")
        val TO = longPreferencesKey("to")
        val LIMIT = intPreferencesKey("limit")
        val MIN_ACC = floatPreferencesKey("min_acc")
    }

    val condition: Flow<SelectorCondition> =
        appContext.selectorDataStore.data.map { p ->
            SelectorCondition(
                mode = p[K.MODE]?.let { runCatching { Mode.valueOf(it) }.getOrDefault(Mode.ByPeriod) } ?: Mode.ByPeriod,
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                limit = p[K.LIMIT],
                minAccuracyM = p[K.MIN_ACC]
            )
        }

    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        appContext.selectorDataStore.edit { p ->
            val curr = SelectorCondition(
                mode = p[K.MODE]?.let { runCatching { Mode.valueOf(it) }.getOrDefault(Mode.ByPeriod) } ?: Mode.ByPeriod,
                fromMillis = p[K.FROM],
                toMillis = p[K.TO],
                limit = p[K.LIMIT],
                minAccuracyM = p[K.MIN_ACC]
            )
            val next = block(curr)
            p[K.MODE] = next.mode.name
            next.fromMillis?.let { p[K.FROM] = it } ?: p.remove(K.FROM)
            next.toMillis?.let { p[K.TO] = it } ?: p.remove(K.TO)
            next.limit?.let { p[K.LIMIT] = it } ?: p.remove(K.LIMIT)
            next.minAccuracyM?.let { p[K.MIN_ACC] = it } ?: p.remove(K.MIN_ACC)
        }
    }
}
