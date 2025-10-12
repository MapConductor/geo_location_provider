package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.prefs.SelectorPrefs
import com.mapconductor.plugin.provider.geolocation._dataselector.repository.SelectorRepository
import com.mapconductor.plugin.provider.geolocation._dataselector.usecase.BuildSelectedRows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 厳密案：ViewModel は app 側のみ。
 * dataselector は純ロジック（Prefs/Repository/UseCase）のみを提供。
 */
class SelectorViewModel(
    app: Application,
    /** 既存の「全件 Flow<List<LocationSample>>」を注入（Daoや既存VMから渡す） */
    baseFlow: Flow<List<LocationSample>>,
    /** LocationSample から比較用のエポックミリ秒を取り出す関数（例：{ it.recordedAt }） */
    getMillis: (LocationSample) -> Long,
    /** LocationSample から精度(m)を取り出す関数（無い場合は { null }） */
    getAccuracy: (LocationSample) -> Float?
) : AndroidViewModel(app) {

    private val prefs = SelectorPrefs(app.applicationContext)

    // Repository はジェネリック版（getMillis / getAccuracy を注入）想定
    private val repo = SelectorRepository(
        baseFlow = baseFlow
    )

    private val buildSelectedRows = BuildSelectedRows(prefs, repo)

    /** 画面が購読する抽出済みリスト */
    val rows: StateFlow<List<LocationSample>> =
        buildSelectedRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 現在の条件（必要ならUIで参照） */
    val condition: StateFlow<SelectorCondition> =
        prefs.condition.stateIn(viewModelScope, SharingStarted.Eagerly, SelectorCondition())

    /** 条件更新（期間/件数/精度） */
    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        prefs.update(block)
    }

    // ------- Factory -------
    class Factory(
        private val app: Application,
        private val baseFlow: Flow<List<LocationSample>>,
        private val getMillis: (LocationSample) -> Long,
        private val getAccuracy: (LocationSample) -> Float?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SelectorViewModel(app, baseFlow, getMillis, getAccuracy) as T
        }
    }
}
