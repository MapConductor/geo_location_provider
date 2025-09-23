package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手動エクスポート用 ViewModel。
 * - exportAll(limit): Downloads へ GeoJSON/ZIP を保存
 * - exportAndUpload(...): 保存後に Drive へアップロード（任意）
 */
class ManualExportViewModel(private val appContext: Context) : ViewModel() {

    /**
     * 既存の手動エクスポート。
     * 末尾 limit 件（null なら全件）をエクスポート（Downloadsへ保存）。
     */
    fun exportAll(limit: Int? = 1000) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(appContext).locationSampleDao()
            val all = dao.findAll() // 昇順
            val data = if (limit != null) all.takeLast(limit) else all

            val uri: Uri? = if (data.isNotEmpty()) {
                GeoJsonExporter.exportToDownloads(appContext, data)
            } else null

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

    /**
     * 手動エクスポート＋Driveアップロード（任意機能）
     */
    fun exportAndUpload(
        limit: Int? = 1000,
        deleteOnSuccess: Boolean = false,
        deleteDbOnSuccess: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(appContext).locationSampleDao()
            val all = dao.findAll() // 昇順
            val data = if (limit != null) all.takeLast(limit) else all

            if (data.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "エクスポート対象のデータがありません", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val uri: Uri? = GeoJsonExporter.exportToDownloads(appContext, data)
            if (uri == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "保存に失敗しました", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            Log.i("GLP-Manual", "Export saved: uri=$uri, count=${data.size}")

            val uploadResult = uploadIfConfigured(uri)

            // 成功時の後処理（ポリシーに応じて）
            if (uploadResult == true) {
                if (deleteDbOnSuccess) {
                    runCatching {
                        val ids = data.mapNotNull { it.id }
                        if (ids.isNotEmpty()) {
                            // 例: @Query("DELETE FROM location_samples WHERE id IN (:ids)") fun deleteByIds(ids: List<Long>)
                            dao.deleteByIds(ids)
                            Log.i("GLP-Manual", "DB rows deleted: ${ids.size}")
                        }
                    }.onFailure { e ->
                        Log.w("GLP-Manual", "DB delete failed: ${e.message}")
                    }
                }
                if (deleteOnSuccess) {
                    runCatching {
                        appContext.contentResolver.delete(uri, null, null)
                        Log.i("GLP-Manual", "Local exported file deleted: $uri")
                    }.onFailure { e ->
                        Log.w("GLP-Manual", "Local file delete failed: ${e.message}")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val msg = when (uploadResult) {
                    true  -> "保存＋アップロード完了"
                    false -> "保存は完了。アップロードはスキップ／失敗"
                    null  -> "保存に失敗しました"
                }
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Drive 設定が揃っている場合にアップロードを実施。
     * @return 成功:true / 失敗:false / 実行不可(null)
     */
    private suspend fun uploadIfConfigured(uri: Uri): Boolean? {
        return runCatching {
            // DataStore から同期取得（IOスレッド）
            val prefs = DrivePrefsRepository(appContext)
            val folderRaw = prefs.folderIdFlow.first()
            val folder = DriveFolderId.extractFromUrlOrId(folderRaw)

            if (folder.isNullOrBlank()) return@runCatching null

            // 認証トークンはインスタンス経由で取得
            val token = GoogleAuthRepository(appContext).getAccessTokenOrNull() ?: return@runCatching null

            val client = DriveApiClient(appContext)
            when (val res = client.uploadMultipart(token = token, uri = uri, folderId = folder)) {
                is UploadResult.Success -> {
                    Log.i("GLP-Drive", "Upload OK: id=${res.id}, name=${res.name}, link=${res.webViewLink}")
                    true
                }
                is UploadResult.Failure -> {
                    Log.w("GLP-Drive", "Upload NG: code=${res.code}, body=${res.body}")
                    false
                }
            }
        }.getOrElse { e ->
            Log.e("GLP-Drive", "Upload exception: ${e.message}", e)
            false
        }
    }

    /** ViewModelProvider.Factory（DI未使用の簡易版） */
    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ManualExportViewModel(appContext) as T
        }
    }

    /** Compose の viewModel(factory = ManualExportViewModel.factory(ctx)) 用のショートカット */
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            Factory(context.applicationContext)
    }
}
