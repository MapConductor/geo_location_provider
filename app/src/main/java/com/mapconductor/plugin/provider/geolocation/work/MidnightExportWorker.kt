package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.util.NotificationHelper
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.core.data.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.data.room.ExportedDay
import com.mapconductor.plugin.provider.geolocation.core.domain.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 日次バックログ対応版:
 * - 「最古の未アップロード日（uploaded=false）」を優先して処理。
 * - 候補がなければ昨日を候補化し、当該日のデータが無ければスキップ。
 * - エクスポート成功時に exportedLocal=true、アップロード成功時に uploaded=true を記録。
 * - アップロードに失敗した場合は DB/ローカルを削除しない（従来方針）。
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.get(applicationContext)
            val dao = db.locationSampleDao()
            val exportedDayDao = db.exportedDayDao()

            // 基準日時（JST）
            val nowJst: ZonedDateTime = ZonedDateTime.now(zone)
            val today0: ZonedDateTime = nowJst.truncatedTo(ChronoUnit.DAYS)
            val yesterdayEpochDay: Long = today0.minusDays(1).toLocalDate().toEpochDay()

            // 最古の未アップロード日を取得。無ければ昨日を候補化（初回など）
            val candidate = exportedDayDao.oldestNotUploaded()
            val targetEpochDay = candidate?.epochDay ?: yesterdayEpochDay
            exportedDayDao.ensure(ExportedDay(epochDay = targetEpochDay))

            // epochDay -> [from,to) ミリ秒へ
            val targetDate: LocalDate = LocalDate.ofEpochDay(targetEpochDay)
            val y0 = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val t0 = targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            // 対象日のレコード取得
            val records = dao.findBetween(y0, t0)
            if (records.isEmpty()) {
                Log.i(
                    LogTags.WORKER,
                    "No records for target day (epochDay=$targetEpochDay); scheduling next and exiting."
                )
                scheduleNext0am()
                return@withContext Result.success()
            }

            // ファイル名（例：glp-YYYYMMDD.geojson/zip）…targetDate 基準
            val baseName = DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(zone)
                .format(targetDate.atStartOfDay(zone).toInstant())
                .let { "glp-$it" }

            // Downloads/GeoLocationProvider へ出力（ZIP）
            val outUri: Uri = GeoJsonExporter
                .exportToDownloads(applicationContext, records, baseName = baseName, compressAsZip = true)
                ?: run {
                    NotificationHelper.notifyPermanentFailure(
                        applicationContext,
                        "Export failed: cannot create file for epochDay=$targetEpochDay."
                    )
                    // 生成失敗を記録（uploadedは立てない）
                    runCatching { exportedDayDao.markError(targetEpochDay, "cannot create file") }
                    scheduleNext0am()
                    return@withContext Result.success()
                }

            // ローカル出力成功を記録
            runCatching { exportedDayDao.markExportedLocal(targetEpochDay) }

            // アップロード設定を確認（NONE の場合はアップロードせず終了）
            val prefs = AppPrefs.uploadSnapshot(applicationContext)
            if (prefs.engine == UploadEngine.NONE) {
                Log.i(LogTags.WORKER, "UploadEngine=NONE; keep local file and DB as-is (epochDay=$targetEpochDay).")
                scheduleNext0am()
                return@withContext Result.success()
            }

            // Drive フォルダの妥当性
            val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
            if (folderId.isNullOrBlank()) {
                NotificationHelper.notifyPermanentFailure(applicationContext, "Drive Folder ID is not configured.")
                runCatching { exportedDayDao.markError(targetEpochDay, "folderId not configured") }
                scheduleNext0am()
                return@withContext Result.success()
            }

            // Uploader 経由でアップロード
            val uploader = UploaderFactory.create(applicationContext, prefs.engine)
            if (uploader == null) {
                Log.i(LogTags.WORKER, "No uploader for engine=${prefs.engine}; skip upload (epochDay=$targetEpochDay).")
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
                    // 成功時のみ：DB とローカルを削除
                    val ids = records.map { it.id }
                    runCatching { applicationContext.contentResolver.delete(outUri, null, null) }
                    runCatching { dao.deleteByIds(ids) }
                    // 日完了を記録（driveFileId も保存）
                    runCatching { exportedDayDao.markUploaded(targetEpochDay, result.id) }
                    Log.i(
                        LogTags.WORKER,
                        "Upload success; cleaned local and DB. day=$targetEpochDay ids=${ids.size}, fileId=${result.id}"
                    )
                }
                is UploadResult.Failure -> {
                    val bodyPreview = result.body.take(200)
                    NotificationHelper.notifyPermanentFailure(
                        applicationContext,
                        "Drive upload failed (HTTP ${result.code}): $bodyPreview"
                    )
                    // データは維持し、エラー内容を記録
                    runCatching { exportedDayDao.markError(targetEpochDay, "${result.code}: $bodyPreview") }
                    Log.w(
                        LogTags.WORKER,
                        "Upload failed; keep local file and DB. day=$targetEpochDay code=${result.code}"
                    )
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
