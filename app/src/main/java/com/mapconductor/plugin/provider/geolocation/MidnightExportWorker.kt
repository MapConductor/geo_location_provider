package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P3: 昨日ぶんのみ内部一時へエクスポートする Worker。
 * - アップロードはしない
 * - DB 削除はしない
 * - 実行後に次回 0:00 を再スケジュール
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val ctx = applicationContext
            val dao = AppDatabase.get(ctx).locationSampleDao()

            // 基準時刻：JSTの“今日の0:00”
            val now = ZonedDateTime.now(zone)
            val today0 = now.toLocalDate().atStartOfDay(zone)
            val yesterday0 = today0.minusDays(1)

            val from = yesterday0.toInstant().toEpochMilli()   // inclusive
            val to   = today0.toInstant().toEpochMilli()       // exclusive

            Log.i(LogTags.WORKER, "Export window [${from} .. ${to}) JST yesterday")

            val records: List<LocationSample> = dao.findBetween(from, to)

            if (records.isEmpty()) {
                Log.i(LogTags.WORKER, "No records in window. Skipping export.")
                // 次回分を予約
                runCatching { MidnightExportScheduler.scheduleNext(ctx) }
                return@withContext Result.success()
            }

            val out = InternalGeoJsonExporter.writeGeoJsonToCache(ctx, records, compress = true)
            if (out == null || !out.exists()) {
                Log.e(LogTags.WORKER, "Export failed: output file not created.")
                // 次回分を予約（失敗時も念のため）
                runCatching { MidnightExportScheduler.scheduleNext(ctx) }
                return@withContext Result.retry()
            }

            // 古い一時の掃除（任意）
            runCatching { InternalGeoJsonExporter.cleanupOldTempFiles(ctx, days = 7) }

            Log.i(LogTags.WORKER, "P3 export completed: ${out.absolutePath}")

            // 正常終了後に“次回0:00”を予約
            runCatching { MidnightExportScheduler.scheduleNext(ctx) }

            Result.success()
        } catch (e: Throwable) {
            Log.e(LogTags.WORKER, "P3 export crashed", e)
            // クラッシュ時も次回分を予約
            runCatching { MidnightExportScheduler.scheduleNext(applicationContext) }
            Result.retry()
        }
    }
}
