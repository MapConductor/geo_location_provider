package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSampleDao
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.prefs.SelectorPrefs
import com.mapconductor.plugin.provider.geolocation._dataselector.usecase.BuildSelectRows
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel は app 側のみ。
 * dataselector は Prefs / UseCase（BuildSelectRows）だけを提供。
 */
class SelectorViewModel(
    app: Application,
    dao: LocationSampleDao,               // ★ 必要なのは DAO だけ
) : AndroidViewModel(app) {

    private val prefs = SelectorPrefs(app.applicationContext)
    private val buildSelectRows = BuildSelectRows(dao)   // ★ 正しい依存性注入

    /** 現在の条件（UIから参照/編集） */
    val condition: StateFlow<SelectorCondition> =
        prefs.condition.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SelectorCondition()
        )

    /** 条件に応じた抽出済みリスト（画面が購読） */
    val rows: StateFlow<List<LocationSample>> =
        condition
            .flatMapLatest { cond -> buildSelectRows(cond) }   // ★ invoke(cond) は Flow<List<…>>
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** 条件更新（期間/件数/精度/間隔/並び順など） */
    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        prefs.update(block)
    }

    // ------- Factory -------
    class Factory(
        private val app: Application,
        private val dao: LocationSampleDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SelectorViewModel(app, dao) as T
        }
    }
}
