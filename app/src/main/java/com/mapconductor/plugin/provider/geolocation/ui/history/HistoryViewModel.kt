package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.Flow

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    /** 画面に映る分だけ描画する。件数はここで指定 */
    val latest: Flow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, 6)
}
