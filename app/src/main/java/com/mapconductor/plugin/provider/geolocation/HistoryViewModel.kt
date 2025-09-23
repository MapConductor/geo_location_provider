package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).locationSampleDao()

    // latest(...) は存在しないため latestFlow(...) に置換
    val latest30: Flow<List<LocationSample>> = dao.latestFlow(30)

    // DAOの suspend 関数 latestOne() を Flow 化（単発emit・自動更新なし）
    val latestOne: Flow<LocationSample?> = flow {
        emit(dao.latestOne())
    }
}
