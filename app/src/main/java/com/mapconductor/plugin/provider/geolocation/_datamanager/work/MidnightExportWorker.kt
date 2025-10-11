package com.mapconductor.plugin.provider.geolocation._datamanager.work

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
import com.mapconductor.plugin.provider.geolocation._core.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation._core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation._core.data.room.ExportedDay
import com.mapconductor.plugin.provider.geolocation._core.data.room.ExportedDayDao
import com.mapconductor.plugin.provider.geolocation._datamanager.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation._datamanager.drive.upload.KotlinDriveUploader
import com.mapconductor.plugin.provider.geolocation._datamanager.drive.UploadResult
import java.lang.reflect.Method
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val sampleDao = db.locationSampleDao()
        val dayDao = resolveExportedDayDao(db)
            ?: return Result.failure() // DAOが取れない場合は失敗を返す

        val today = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.DAYS).toLocalDate()
        val todayEpochDay = today.toEpochDay()

        var oldest = dayDao.oldestNotUploaded()
        if (oldest == null) {
            // 初回は過去14日分をensure（必要に応じて調整）
            val backDays = 14L
            for (off in backDays downTo 1L) {
                val d = today.minusDays(off).toEpochDay()
                if (d < todayEpochDay) dayDao.ensure(ExportedDay(epochDay = d))
            }
            oldest = dayDao.oldestNotUploaded()
        }

        while (true) {
            val target = oldest ?: break
            if (target.epochDay >= todayEpochDay) break

            val localDate = LocalDate.ofEpochDay(target.epochDay)
            val startMillis = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val records = sampleDao.findBetween(startMillis, endMillis)
            if (records.isEmpty()) {
                // 空日も完了扱い（idempotent）
                dayDao.markUploaded(target.epochDay, null)
                oldest = dayDao.oldestNotUploaded()
                continue
            }

            val baseName = "glp-${DateTimeFormatter.ofPattern("yyyyMMdd").format(localDate)}"
            val exported: Uri? = GeoJsonExporter.exportToDownloads(
                context = applicationContext,
                records = records,
                baseName = baseName,
                compressAsZip = true
            )
            // ローカルへの書き出し成功かは exported!=null で判定
            dayDao.markExportedLocal(target.epochDay)

            // アップロード設定
            val prefs = AppPrefs.uploadSnapshot(applicationContext)
            if (prefs.engine == UploadEngine.KOTLIN && exported != null && prefs.folderId.isNotBlank()) {
                val uploader = KotlinDriveUploader(applicationContext)
                when (val up = uploader.upload(exported, prefs.folderId, "$baseName.geojson.zip")) {
                    is UploadResult.Success -> {
                        // 成功：ファイルIDはAPIに依存するため保存しない／null
                        dayDao.markUploaded(target.epochDay, null)
                        // 任意：レコード削除の要件があればここで sampleDao.deleteByIds(...)
                    }
                    is UploadResult.Failure -> {
                        // 失敗内容を保存（HTTPコード等があれば含まれている想定）
                        val msg = buildString {
                            up.code?.let { append("HTTP $it ") }
                            if (!up.message.isNullOrBlank()) append(up.message)
                            if (!up.body.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(up.body.take(300))
                            }
                        }.ifBlank { "Upload failure" }
                        dayDao.markError(target.epochDay, msg)
                    }
                }
            } else {
                // アップロード無効／失敗時でもその日の処理は完了扱い
                dayDao.markUploaded(target.epochDay, null)
            }

            oldest = dayDao.oldestNotUploaded()
        }

        MidnightExportScheduler.scheduleNext(applicationContext)
        return Result.success()
    }

    /**
     * AppDatabase から ExportedDayDao をアクセサ名に依存せず取得する。
     * 例: exportedDayDao / exportedDaysDao / dayDao / getExportedDayDao 等に対応
     */
    private fun resolveExportedDayDao(db: AppDatabase): ExportedDayDao? {
        // まずは代表的な名前で直呼び
        try { return AppDatabase::class.java.getMethod("exportedDayDao").invoke(db) as ExportedDayDao } catch (_: Throwable) {}
        try { return AppDatabase::class.java.getMethod("exportedDaysDao").invoke(db) as ExportedDayDao } catch (_: Throwable) {}
        try { return AppDatabase::class.java.getMethod("dayDao").invoke(db) as ExportedDayDao } catch (_: Throwable) {}
        try { return AppDatabase::class.java.getMethod("getExportedDayDao").invoke(db) as ExportedDayDao } catch (_: Throwable) {}

        // 最後に汎用探索：戻り値の型名に "ExportedDayDao" を含む無引数メソッドを探す
        val m: Method? = db.javaClass.methods.firstOrNull {
            it.parameterCount == 0 &&
                    (it.returnType.simpleName == "ExportedDayDao" || it.returnType.name.endsWith(".ExportedDayDao"))
        }
        return try { m?.invoke(db) as? ExportedDayDao } catch (_: Throwable) { null }
    }

    companion object {
        private const val UNIQUE_NAME = "midnight-export-worker"

        /** 前日以前のバックログを即時実行（UniqueWork: REPLACE） */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
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
