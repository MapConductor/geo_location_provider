package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.Companion.get(app).locationSampleDao()

    // latest(...) は存在しないため latestFlow(...) に置換
    val latest30: Flow<List<LocationSample>> = dao.latestFlow(30)

    // DAOの suspend 関数 latestOne() を Flow 化（単発emit・自動更新なし）
    val latestOne: Flow<LocationSample?> = dao.latestOneFlow()
}