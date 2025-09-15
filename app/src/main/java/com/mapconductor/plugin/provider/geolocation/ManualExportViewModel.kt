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

    /** ボタン押下で呼ぶ：末尾 limit 件（nullなら全件相当）をエクスポート */
    fun exportAll(limit: Int? = 1000) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(appContext).locationSampleDao()

            // DAOに latestList(limit) がある前提。null の場合は十分大きい値を渡して“実質全件”
            val take = limit ?: Int.MAX_VALUE
            val data = dao.latestList(take)   // 新しい順で最大 take 件

            val uri = GeoJsonExporter.exportToDownloads(appContext, data, compressAsZip = true)

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
