package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mapconductor.plugin.provider.geolocation.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import kotlinx.coroutines.flow.Flow

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.Companion.get(app).locationSampleDao()

    /** 画面に映る分だけ描画するため、DAO側では上限を設けない */
    val latest: Flow<List<LocationSample>> = dao.latestFlow(6)

    // DAOの suspend 関数 latestOne() を Flow 化（単発emit・自動更新なし）
    val latestOne: Flow<LocationSample?> = dao.latestOneFlow()
}
