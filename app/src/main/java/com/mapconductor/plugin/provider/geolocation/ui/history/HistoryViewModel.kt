package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * 履歴取得用のVM。
 * - Room へは直接触れず、必ず StorageService ファサード経由に統一
 * - こうしておくと将来のモジュール分離／移動でもUI側のコード変更を最小にできる
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    /** 画面に映る分だけ描画するため、DAO側では上限を設けない（ここでlimit指定） */
    val latest: Flow<List<LocationSample>> =
        StorageService.latestFlow(getApplication(), limit = 6)

    /** 最新1件（Flow） */
    val latestOne: Flow<LocationSample?> =
        StorageService.latestOneFlow(getApplication())
}
