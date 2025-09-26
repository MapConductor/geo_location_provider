package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mapconductor.plugin.provider.geolocation.core.data.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.core.data.prefs.UploadPrefs
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.domain.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ManualExportViewModel(private val appContext: Context) : ViewModel() {

    /**
     * ボタン押下で呼ぶ：末尾 limit 件（nullなら全件）をエクスポート（ZIPで保存）
     * alsoUpload=true の場合は、設定が有効なら Drive にも送る（DBは削除しない）
     */
    fun exportAll(limit: Int? = 1000, alsoUpload: Boolean = false) {
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

            // ファイル名（例：glp-20250923-2130.geojson/zip）
            val nowJst: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
            val baseName = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.of("Asia/Tokyo"))
                .format(nowJst.truncatedTo(ChronoUnit.SECONDS))
                .let { "glp-$it" }

            val uri = GeoJsonExporter.exportToDownloads(
                context = appContext,
                records = data,
                baseName = baseName,
                compressAsZip = true
            )

            val msg = if (uri != null) {
                // オプションで Drive にもアップロード（DBは削除しない）
                if (alsoUpload) {
                    val prefs = AppPrefs.uploadSnapshot(appContext)
                    val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
                    val uploader = UploaderFactory.create(appContext, prefs.engine)
                    if (uploader != null && !folderId.isNullOrBlank()) {
                        val result = uploader.upload(uri, folderId)
                        // 成否はトーストに反映（詳細は通知に出さず簡潔に）
                        if (result is UploadResult.Success) {
                            "保存＆Driveにアップロードしました: $uri"
                        } else {
                            "保存しました（Driveアップロード失敗）: $uri"
                        }
                    } else {
                        "保存しました（アップロード設定なし）: $uri"
                    }
                } else {
                    "保存しました: $uri"
                }
            } else {
                "保存に失敗しました"
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** Compose の viewModel(factory = ...) から呼ぶための Factory */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManualExportViewModel(context.applicationContext) as T
                }
                // 新しいシグネチャ（CreationExtras付き）にも対応
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    return ManualExportViewModel(context.applicationContext) as T
                }
            }
    }
}
