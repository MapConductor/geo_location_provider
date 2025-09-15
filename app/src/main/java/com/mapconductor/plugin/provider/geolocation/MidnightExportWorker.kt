package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.GeoJsonExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import androidx.room.withTransaction

class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 最大5回までリトライ（attempt は 0 始まり）
        if (runAttemptCount >= 5) {
            // 失敗通知
            ExportNotify.notifyPermanentFailure(applicationContext, "GeoJSON backup failed after 5 retries")
            // 次回 0:00 を再予約（ここで止めず、日次は回す）
            MidnightExportScheduler.scheduleNext(applicationContext)
            return@withContext Result.failure()
        }

        // 「実行時刻が何時でも」当日0:00基準で切る
        val cutoff = ZonedDateTime.now(zone)
            .truncatedTo(ChronoUnit.DAYS) // 当日 0:00（JST）
            .toInstant().toEpochMilli()

        val db = AppDatabase.get(applicationContext)
        val dao = db.locationSampleDao()

        // 0:00 より前のデータを抽出
        val targets = dao.findBefore(cutoff)

        if (targets.isEmpty()) {
            // 次回スケジュールだけ掛け直して成功扱い
            MidnightExportScheduler.scheduleNext(applicationContext)
            return@withContext Result.success()
        }

        // GeoJSON 出力
        val uri = GeoJsonExporter.exportToDownloads(applicationContext, targets)
        if (uri == null) {
            // 失敗 → Step.3 のリトライは Backoff で自動(1分)。ここでは retry。
            return@withContext Result.retry()
        }

        // 出力成功 → 対象レコード削除（トランザクション推奨）
        db.withTransaction {
            val ids = targets.mapNotNull { it.id }
            if (ids.isNotEmpty()) {
                dao.deleteByIds(ids)   // ← OK（withTransaction は suspend）
            }
        }

        // 次回 0:00 を再スケジュール
        MidnightExportScheduler.scheduleNext(applicationContext)

        Result.success()
    }
}
