package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mapconductor.plugin.provider.geolocation.core.data.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.domain.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 定時バックアップ（毎日 0:00 JST）と「前日以前をBackup（即時）」で実行されるコアのワーカー。
 *
 * 仕様（確定版）:
 * - 対象: 前日より前の全データを日付ごとに処理（古い日から）
 * - ファイル名: glp-YYYYMMDD.zip（対象日ベース）
 * - リトライ: 初回 + 4回 = 計5回（15s, 30s, 60s, 120s バックオフ）
 * - 成功: ZIP削除 + 対象日の Room レコード削除
 * - 失敗（5回とも失敗）: ZIP削除 / Room保持（翌日以降に再送）。当該日で打ち切り、残りは翌日に回す
 *
 * 備考:
 * - UniqueWork名: "MidnightExportWorkerUnique"
 * - ネットワーク必須 Constraints
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.get(applicationContext)
            val dao = db.locationSampleDao()

            // Upload設定
            val prefs = AppPrefs.uploadSnapshot(applicationContext)
            val folderId = DriveFolderId.extractFromUrlOrId(prefs.folderId)
            val uploader = UploaderFactory.create(applicationContext, prefs.engine)

            if (uploader == null || folderId.isNullOrBlank()) {
                Log.w(TAG, "Uploader not ready: engine=${prefs.engine}, folderId=$folderId")
                // 次回の0:00に任せる
                scheduleNext0am()
                return@withContext Result.success()
            }

            // 今日(0:00)より前を対象とする
            val nowJst = ZonedDateTime.now(zone)
            val today0 = nowJst.truncatedTo(ChronoUnit.DAYS)
            val todayEpochDay = today0.toLocalDate().toEpochDay()

            // すべて読み出してJSTのepochDayでグルーピング（古い順）
            val all = dao.findAll()
            if (all.isEmpty()) {
                scheduleNext0am()
                return@withContext Result.success()
            }

            val grouped = all.groupBy { rec ->
                // ★ LocationSample のタイムスタンプは createdAt(Long) を使用します
                val millis = rec.createdAt
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
                    .toLocalDate()
                    .toEpochDay()
            }.toSortedMap()

            for ((epochDay, records) in grouped) {
                if (epochDay >= todayEpochDay) continue // 今日分は対象外

                val localDate = LocalDate.ofEpochDay(epochDay)
                val baseName = "glp-${DateTimeFormatter.ofPattern("yyyyMMdd").format(localDate)}"

                // ZIP生成（Downloads/GeoLocationProvider）
                var outUri: Uri? = null
                try {
                    outUri = GeoJsonExporter.exportToDownloads(
                        context = applicationContext,
                        records = records,
                        baseName = baseName,
                        compressAsZip = true
                    )
                    if (outUri == null) {
                        Log.w(TAG, "ZIP create failed for $baseName; skip this day")
                        // ZIP無ければ削除不要。次回以降Roomから再生成される
                        continue
                    }

                    // 最大5回試行（初回+4回リトライ）
                    var success = false
                    for (attempt in 0 until 5) {
                        when (val result = uploader.upload(outUri, folderId, null)) {
                            is UploadResult.Success -> {
                                // 成功: ZIP削除 + 対象日Room削除（既存処理のまま）
                                runCatching { applicationContext.contentResolver.delete(outUri, null, null) }
                                val ids = records.map { it.id }
                                runCatching { dao.deleteByIds(ids) }
                                success = true
                                break
                            }
                            is UploadResult.Failure -> {
                                if (attempt < 4) {
                                    delay(15_000L * (1 shl attempt))
                                }
                            }
                        }
                    }

                    if (!success) {
                        // 失敗確定: ZIP削除 / Room保持（翌日以降に再送）
                        runCatching { applicationContext.contentResolver.delete(outUri, null, null) }
                        Log.w(TAG, "Upload failed after retries day=$localDate. Keep Room, drop ZIP. Stop here for today.")
                        // ここで打ち切り（残りは翌日に回す）
                        break
                    }
                } finally {
                    // 念のため ZIPハンドルの掃除（上で消せてなくてもここで消す）
                    if (outUri != null) {
                        runCatching { applicationContext.contentResolver.delete(outUri, null, null) }
                    }
                }
            }

            scheduleNext0am()
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "MidnightExportWorker fatal", t)
            scheduleNext0am()
            Result.success() // 次回に任せる
        }
    }

    private fun scheduleNext0am() {
        // 既存のスケジューラ（同パッケージ）に委譲
        try {
            MidnightExportScheduler.scheduleNext(applicationContext)
        } catch (_: Throwable) {
            // フォールバック: UniqueWorkをネット必須で1回（次の0:00への合わせはスケジューラ側に委任）
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                req
            )
        }
    }

    companion object {
        private const val TAG = "MidnightExportWorker"
        const val UNIQUE_NAME = "MidnightExportWorkerUnique"

        /** 「前日以前をBackup」ボタンから即時実行するためのユーティリティ */
        fun runBacklogNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE, // 即時実行は置き換え
                req
            )
        }
    }
}
