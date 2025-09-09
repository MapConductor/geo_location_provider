package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.Flow

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).locationSampleDao()

    val latest30: Flow<List<LocationSample>> = dao.latest(30)

    // ★ 追加：最新1件
    val latestOne: Flow<LocationSample?> = dao.latestOne()
}
