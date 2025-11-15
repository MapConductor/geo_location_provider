package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 定時バックアップ（前日以前のバックログ処理も包含）
 *
 * - LocationSample / ExportedDay ともに storageservice の DB を使用し、
 *   ここからは StorageService 経由でのみアクセスする。
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    override suspend fun doWork(): Result {
        val today = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toLocalDate()
        val todayEpochDay = today.toEpochDay()

        // --- 初回シード：昨日までの最大365日を ensure --- //
        var oldest = StorageService.oldestNotUploadedDay(applicationContext)
        if (oldest == null) {
            val backMaxDays = 365L
            for (off in backMaxDays downTo 1L) {
                val d = today.minusDays(off).toEpochDay()
                if (d < todayEpochDay) {
                    StorageService.ensureExportedDay(applicationContext, d)
                }
            }
            oldest = StorageService.oldestNotUploadedDay(applicationContext)
        }

        // --- バックログ処理ループ（今日以降は対象外） --- //
        while (oldest != null && oldest.epochDay < todayEpochDay) {
            val target = oldest
            val localDate = LocalDate.ofEpochDay(target.epochDay)
            val startMillis = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val baseName = "glp-${dateFmt.format(localDate)}"

            // 1日のレコード取得（storageservice 側 DB を StorageService 経由で読む）
            val records = StorageService.getLocationsBetween(
                ctx = applicationContext,
                from = startMillis,
                to = endMillis,
                softLimit = 1_000_000
            )

            // GeoJSON を ZIP でローカル生成
            val exported: Uri? = GeoJsonExporter.exportToDownloads(
                context = applicationContext,
                records = records,
                baseName = baseName,
                compressAsZip = true
            )

            // ローカル出力成功の印（空日でも成功扱い）
            StorageService.markExportedLocal(applicationContext, target.epochDay)

            // アップロード（設定が揃っている場合のみ）
            val prefs = AppPrefs.snapshot(applicationContext)
            val engineOk = (prefs.engine == UploadEngine.KOTLIN)
            val folderOk = !prefs.folderId.isNullOrBlank()
            val uploader = if (engineOk && folderOk) {
                UploaderFactory.create(applicationContext, prefs.engine)
            } else null

            var uploadSucceeded = false
            var lastError: String? = null

            if (exported == null) {
                lastError = "No file exported"
                StorageService.markExportError(applicationContext, target.epochDay, lastError)
            } else if (uploader == null) {
                lastError = when {
                    !engineOk -> "Upload not configured (engine=${prefs.engine})"
                    !folderOk -> "Upload not configured (folderId missing)"
                    else -> "Uploader disabled"
                }
                StorageService.markExportError(applicationContext, target.epochDay, lastError)
            } else {
                when (val up = uploader.upload(exported, prefs.folderId!!, "$baseName.zip")) {
                    is UploadResult.Success -> {
                        uploadSucceeded = true
                        StorageService.markUploaded(applicationContext, target.epochDay, null)
                    }
                    is UploadResult.Failure -> {
                        lastError = buildString {
                            up.code?.let { append("HTTP $it ") }
                            if (!up.message.isNullOrBlank()) append(up.message)
                            if (!up.body.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(up.body.take(300))
                            }
                        }.ifBlank { "Upload failure" }
                        StorageService.markExportError(applicationContext, target.epochDay, lastError)
                    }
                }
            }

            // ZIP は必ず削除
            exported?.let { safeDelete(it) }

            // 成功時のみ当日のレコードを削除（storageservice 側の DB）
            if (uploadSucceeded && records.isNotEmpty()) {
                try {
                    StorageService.deleteLocations(applicationContext, records)
                } catch (_: Throwable) {
                    // 失敗は致命ではない（次周で再対象）
                }
            }

            // 次の未アップロード日へ
            oldest = StorageService.oldestNotUploadedDay(applicationContext)
        }

        // 実行後は翌日0:00に再スケジュール
        scheduleNext(applicationContext)
        return Result.success()
    }

    // ---- ここからはクラスメンバ（ローカル関数にしない） ----

    private fun scheduleNext(context: Context) {
        val delayMs = calcDelayUntilNextMidnightMillis()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun calcDelayUntilNextMidnightMillis(): Long {
        val now = ZonedDateTime.now(zone)
        val next = now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    private fun safeDelete(uri: Uri) {
        try {
            applicationContext.contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {
            // 端末/権限状況により失敗することがあるが致命ではない
        }
    }

    companion object {
        private const val UNIQUE_NAME = "midnight-export-worker"

        /** UI から即時にバックログ処理を起動するためのヘルパー */
        @JvmStatic
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
