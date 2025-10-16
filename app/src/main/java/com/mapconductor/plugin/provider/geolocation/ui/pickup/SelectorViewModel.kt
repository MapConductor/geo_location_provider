package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSampleDao
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation._dataselector.prefs.SelectorPrefs
import com.mapconductor.plugin.provider.geolocation._dataselector.repository.SelectorRepository
import com.mapconductor.plugin.provider.geolocation._dataselector.usecase.BuildSelectedSlots
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel は app 側のみ。
 * dataselector は Prefs / Repository / UseCase(BuildSelectedSlots) を提供。
 */
class SelectorViewModel(
    app: Application,
    dao: LocationSampleDao,               // ★ 必要なのは DAO だけ
) : AndroidViewModel(app) {

    private val prefs = SelectorPrefs(app.applicationContext)
    private val repo = SelectorRepository(dao)
    private val buildSelectedSlots = BuildSelectedSlots(repo)

    /** 現在の条件（UIから参照/編集） */
    val condition: StateFlow<SelectorCondition> =
        prefs.condition.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SelectorCondition()
        )

    /** 条件に応じた抽出済みスロット（欠測補完を含む） */
    val slots: StateFlow<List<SelectedSlot>> =
        condition
            .mapLatest { cond -> buildSelectedSlots(cond) } // suspend -> List を Flow 化
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
