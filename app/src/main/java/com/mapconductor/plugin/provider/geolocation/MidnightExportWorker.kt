package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 「当日0:00」を基準に切り、「昨日分のみ」をエクスポート対象にする Worker。
 * エクスポート成功時は、Drive 設定が有効であればアップロードも行う。
 * （アップロード成功時のみ DB/ローカルファイルの削除を行う）
 */
class MidnightExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // 1) 昨日 0:00 ～ 今日 0:00 のミリ秒範囲を求める
            val (fromMs, toMs) = yesterdayWindowMillis()

            // 2) DB から対象レコードを範囲取得（createdAt 基準）
            val dao = AppDatabase.get(applicationContext).locationSampleDao()
            val targets = dao.findBetween(fromMs, toMs) // ← findAll() + filter ではなく範囲クエリを使用

            if (targets.isEmpty()) {
                Log.i("GLP-Worker", "No records to export. window=[$fromMs, $toMs)")
                scheduleNext0am()
                return@withContext Result.success()
            }

            // 3) GeoJSON（またはZIP）にエクスポート
            val uri: Uri? = GeoJsonExporter.exportToDownloads(applicationContext, targets)
            if (uri == null) {
                Log.w("GLP-Worker", "Export failed: window=[$fromMs, $toMs)")
                scheduleNext0am()
                return@withContext Result.success()
            }
            Log.i("GLP-Worker", "Export saved: uri=$uri, count=${targets.size}")

            // 4) Drive へアップロード（設定が揃っている場合のみ）
            val uploaded: Boolean = uploadIfConfigured(uri) == true

            // 5) 後処理ポリシー：
            //    - アップロード成功時のみ、DB の該当レコード削除＆ローカルファイル削除
            if (uploaded) {
                runCatching {
                    val ids = targets.mapNotNull { it.id }
                    if (ids.isNotEmpty()) {
                        dao.deleteByIds(ids)
                        Log.i("GLP-Worker", "DB rows deleted: ${ids.size}")
                    }
                }.onFailure { e ->
                    Log.w("GLP-Worker", "DB delete failed: ${e.message}")
                }

                runCatching {
                    applicationContext.contentResolver.delete(uri, null, null)
                    Log.i("GLP-Worker", "Local exported file deleted: $uri")
                }.onFailure { e ->
                    Log.w("GLP-Worker", "Local file delete failed: ${e.message}")
                }
            } else {
                Log.i("GLP-Worker", "Upload skipped or failed. Keep DB/local for retry.")
            }

            // 6) 次回 0:00 を予約
            scheduleNext0am()
            Result.success()
        } catch (t: Throwable) {
            Log.e("GLP-Worker", "Unexpected error: ${t.message}", t)
            // 重大障害時も次回を予約して終了（必要なら Result.retry() に切替）
            scheduleNext0am()
            Result.success()
        }
    }

    /** 昨日 0:00 ～ 今日 0:00 のミリ秒範囲（半開区間 [from, to)）を返す */
    private fun yesterdayWindowMillis(): Pair<Long, Long> {
        val now = ZonedDateTime.now(zone)
        val today0 = now.truncatedTo(ChronoUnit.DAYS) // 今日の 0:00
        val yesterday0 = today0.minusDays(1)          // 昨日の 0:00
        val fromMs = yesterday0.toInstant().toEpochMilli()
        val toMs = today0.toInstant().toEpochMilli()
        Log.i(
            "GLP-Worker",
            "Export window [from..to) = [${yesterday0}, ${today0}) ms=[$fromMs,$toMs)"
        )
        return fromMs to toMs
    }

    /**
     * Drive へアップロード（設定が揃っていれば実行）。
     * - UploadPrefs からエンジン/フォルダIDを取得（Flow→firstOrNull）
     * - GoogleAuthRepository からアクセストークンを取得（suspend）
     * - DriveApiClient.uploadMultipart(...) を実行
     * @return 成功なら true / 失敗 false / 実行不可 null
     */
    private suspend fun uploadIfConfigured(uri: Uri): Boolean? {
        return runCatching {
            // 設定（DataStore）を取得
            val engine = UploadPrefs.engineFlow(applicationContext).firstOrNull() ?: UploadEngine.NONE
            val folderRaw = UploadPrefs.folderIdFlow(applicationContext).firstOrNull()
            val folder = DriveFolderId.extractFromUrlOrId(folderRaw)

            if (engine != UploadEngine.KOTLIN) {
                Log.i("GLP-Drive", "Upload engine is not KOTLIN. Skip.")
                return@runCatching null
            }
            if (folder.isNullOrBlank()) {
                Log.i("GLP-Drive", "Drive folderId not configured. Skip.")
                return@runCatching null
            }

            // アクセストークン（suspend）
            val token = GoogleAuthRepository(applicationContext).getAccessTokenOrNull()
            if (token.isNullOrBlank()) {
                Log.w("GLP-Drive", "No access token. Skip.")
                return@runCatching null
            }

            val client = DriveApiClient(applicationContext)
            when (val res = client.uploadMultipart(token = token, uri = uri, folderId = folder)) {
                is UploadResult.Success -> {
                    Log.i(
                        "GLP-Drive",
                        "Upload OK: id=${res.id}, name=${res.name}, link=${res.webViewLink}"
                    )
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

    /**
     * 次回 0:00 の OneTimeWork を予約。
     * 既存のスケジューラがある場合はそちらに委譲してください。
     */
    private fun scheduleNext0am() {
        try {
            MidnightExportScheduler.scheduleNext(applicationContext)
        } catch (t: Throwable) {
            Log.w("GLP-Worker", "Failed to delegate scheduleNext(): ${t.message}")
        }
    }
}
