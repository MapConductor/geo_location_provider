package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManualExportViewModel(private val appContext: Context) : ViewModel() {
    /** ボタン押下で呼ぶ：末尾 limit 件（nullなら全件）をエクスポート（ZIPで保存） */
    fun exportAll(limit: Int? = 1000) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(appContext).locationSampleDao()

            val data: List<LocationSample> = if (limit != null) {
                // latestList は降順なので、GeoJSONを時系列にしたければ反転
                dao.latestList(limit).asReversed()
            } else {
                // 全件昇順
                dao.findAllAsc()
            }

            val uri = GeoJsonExporter.exportToDownloads(
                context = appContext,
                records = data,
                compressAsZip = true
            )

            withContext(Dispatchers.Main) {
                val msg = when {
                    data.isEmpty() -> "エクスポート対象のデータがありません"
                    uri != null    -> "保存しました: $uri"
                    else           -> "保存に失敗しました"
                }
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManualExportViewModel(context.applicationContext) as T
                }
            }
    }
}
