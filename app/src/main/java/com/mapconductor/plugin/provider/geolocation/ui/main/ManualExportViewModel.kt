package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 今日のPreview：アップロード有無の選択 */
enum class TodayPreviewMode {
    /** アップロードする（成功/失敗に関わらず ZIP を削除。Room は削除しない） */
    UPLOAD_AND_DELETE_LOCAL,
    /** アップロードしない（Downloads に保存、ZIP は保持。Room は削除しない） */
    SAVE_TO_DOWNLOADS_ONLY
}

class ManualExportViewModel(
    private val appContext: Context
) : ViewModel() {

    /**
     * 仕様（確定版）:
     * - 対象: 今日 0:00〜現在 の全レコードを 1 ファイルにまとめる
     * - ファイル名: glp-YYYYMMDD-HHmmss.zip（実行時刻ベース）
     * - モード:
     *   - UPLOAD_AND_DELETE_LOCAL:
     *      - 最大5回試行。成功/失敗を問わず ZIP は必ず削除。Room は削除しない
     *   - SAVE_TO_DOWNLOADS_ONLY:
     *      - Downloads に保存（ZIP保持）。Room は削除しない
     */
    fun backupToday(mode: TodayPreviewMode) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(appContext).locationSampleDao()

            // 今日0:00〜現在
            val zone = ZoneId.of("Asia/Tokyo")
            val now = ZonedDateTime.now(zone)
            val today0 = now.truncatedTo(ChronoUnit.DAYS)
            val startMillis = today0.toInstant().toEpochMilli()
            val endMillis = now.toInstant().toEpochMilli()

            // DAO に findBetween がある前提。無ければ findAll でフィルタに差し替えてください。
            val todays = try {
                dao.findBetween(startMillis, endMillis)
            } catch (_: Throwable) {
                dao.findAll().filter { rec ->
                    // エンティティのフィールド名が異なる場合に備え、反射で安全取得
                    val candidates = arrayOf(
                        "timestampMillis", "timeMillis", "createdAtMillis",
                        "timestamp", "createdAt", "recordedAt", "epochMillis"
                    )
                    val ms = candidates.firstNotNullOfOrNull { name ->
                        runCatching {
                            val f = rec.javaClass.getDeclaredField(name)
                            f.isAccessible = true
                            (f.get(rec) as? Number)?.toLong()
                        }.getOrNull()
                    } ?: 0L
                    ms in startMillis..endMillis
                }
            }

            if (todays.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "今日のデータがありません", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val baseName = "glp-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now)
            var outUri: Uri? = null
            try {
                outUri = GeoJsonExporter.exportToDownloads(
                    context = appContext,
                    records = todays,
                    baseName = baseName,
                    compressAsZip = true
                )
                if (outUri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "ZIPの作成に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                when (mode) {
                    TodayPreviewMode.SAVE_TO_DOWNLOADS_ONLY -> {
                        // そのまま保存して終了（ZIP保持、Room保持）
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "保存しました（Downloads）", Toast.LENGTH_SHORT).show()
                        }
                    }
                    TodayPreviewMode.UPLOAD_AND_DELETE_LOCAL -> {
                        val prefs = AppPrefs.snapshot(appContext)
                        val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
                        val uploader = UploaderFactory.create(appContext, prefs.engine)

                        if (uploader == null || folderId.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appContext, "アップロード設定が不十分です（フォルダや認証を確認）", Toast.LENGTH_LONG).show()
                            }
                            // ZIPは方針に基づき削除（Room保持）
                            runCatching { appContext.contentResolver.delete(outUri, null, null) }
                            return@launch
                        }

                        var success = false
                        for (attempt in 0 until 5) {
                            when (val result = uploader.upload(outUri, folderId, null)) {
                                is UploadResult.Success -> {
                                    // 成功：当モードでは Room は削除しない
                                    success = true
                                    break
                                }
                                is UploadResult.Failure -> {
                                    if (attempt < 4) {
                                        // 15s, 30s, 60s, 120s の指数バックオフ
                                        delay(15_000L * (1 shl attempt))
                                    }
                                }
                            }
                        }

                        // 成功/失敗に関係なく ZIP は削除。Room は保持
                        runCatching { appContext.contentResolver.delete(outUri, null, null) }

                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(appContext, "今日分をアップロードしました（ZIP削除済）", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(appContext, "今日分のアップロードに失敗しました（再試行も不成功）。ZIPは破棄しました", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } finally {
                // 念のため（UPLOAD_AND_DELETE_LOCAL のみ二重削除になっても OK / 保存のみは保持）
                if (mode == TodayPreviewMode.UPLOAD_AND_DELETE_LOCAL && outUri != null) {
                    runCatching { appContext.contentResolver.delete(outUri, null, null) }
                }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
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
