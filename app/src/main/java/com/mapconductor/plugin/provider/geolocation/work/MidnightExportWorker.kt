package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.util.NotificationHelper
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.core.data.prefs.UploadPrefs
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.domain.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 「当日0:00」を基準に切り、「昨日分のみ」をエクスポート対象にする Worker。
 * エクスポート成功時は、Drive 設定が有効かつアップロード成功の場合のみ DB/ローカル削除を行う。
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.get(applicationContext).locationSampleDao()

            // JSTの「昨日」区間 [y0, t0)
            val nowJst: ZonedDateTime = ZonedDateTime.now(zone)
            val today0: ZonedDateTime = nowJst.truncatedTo(ChronoUnit.DAYS)
            val y0 = today0.minusDays(1).toInstant().toEpochMilli()
            val t0 = today0.toInstant().toEpochMilli()

            val records = dao.findBetween(y0, t0)
            if (records.isEmpty()) {
                Log.i(LogTags.WORKER, "No records for yesterday; scheduling next and exiting.")
                scheduleNext0am()
                return@withContext Result.success()
            }

            // ファイル名（例：glp-20250922.geojson/zip）
            val baseName = DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(zone)
                .format(today0.minusDays(1).toInstant())
                .let { "glp-$it" }

            // Downloads/GeoLocationProvider へ出力（ZIPで格納）
            val outUri: Uri = GeoJsonExporter
                .exportToDownloads(applicationContext, records, baseName = baseName, compressAsZip = true)
                ?: run {
                    NotificationHelper.notifyPermanentFailure(applicationContext, "Export failed: cannot create file.")
                    scheduleNext0am()
                    return@withContext Result.success()
                }

            // アップロード設定（NONE の場合はアップロードしない）
            val prefs = UploadPrefs.snapshot(applicationContext)
            if (prefs.engine == UploadEngine.NONE) {
                Log.i(LogTags.WORKER, "UploadEngine=NONE; keep local file and DB as-is.")
                scheduleNext0am()
                return@withContext Result.success()
            }

            // Drive へアップロード
            val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
            if (folderId.isNullOrBlank()) {
                NotificationHelper.notifyPermanentFailure(applicationContext, "Drive Folder ID is not configured.")
                scheduleNext0am()
                return@withContext Result.success()
            }

            // Uploader 経由に一本化
            val uploader = UploaderFactory.create(applicationContext, prefs.engine)
            if (uploader == null) {
                Log.i(LogTags.WORKER, "No uploader for engine=${prefs.engine}; skip upload.")
                scheduleNext0am()
                return@withContext Result.success()
            }
            val result = uploader.upload(
                uri = outUri,
                folderId = folderId,
                fileName = null // Export 側 DISPLAY_NAME を利用
            )

            when (result) {
                is UploadResult.Success -> {
                    // 成功時のみ DB とローカルを削除
                    val ids = records.map { it.id }
                    runCatching { applicationContext.contentResolver.delete(outUri, null, null) }
                    runCatching { dao.deleteByIds(ids) }
                    Log.i(
                        LogTags.WORKER,
                        "Upload success; local file and DB rows deleted. ids=${ids.size}, fileId=${result.id}"
                    )
                }
                is UploadResult.Failure -> {
                    val bodyPreview = result.body.take(200)
                    NotificationHelper.notifyPermanentFailure(
                        applicationContext,
                        "Drive upload failed (HTTP ${result.code}): $bodyPreview"
                    )
                    Log.w(LogTags.WORKER, "Upload failed; keep local file and DB. code=${result.code}")
                }
            }

            scheduleNext0am()
            Result.success()
        } catch (t: Throwable) {
            Log.e(LogTags.WORKER, "Midnight export failed", t)
            // 次回 0:00 の予約は常に行う
            scheduleNext0am()
            Result.success()
        }
    }

    /** 次回 0:00 の OneTimeWork を予約（スケジューラへ委譲） */
    private fun scheduleNext0am() {
        try {
            MidnightExportScheduler.scheduleNext(applicationContext)
        } catch (t: Throwable) {
            Log.w(LogTags.WORKER, "Failed to delegate scheduleNext(): ${t.message}")
        }
    }
}
