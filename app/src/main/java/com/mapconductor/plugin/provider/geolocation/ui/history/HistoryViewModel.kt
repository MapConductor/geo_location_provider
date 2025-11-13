package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    /** 画面に映る分だけ描画する。件数はここで指定 */
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 8)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
