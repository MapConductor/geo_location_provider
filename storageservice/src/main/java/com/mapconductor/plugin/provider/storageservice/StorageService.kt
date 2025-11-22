package com.mapconductor.plugin.provider.storageservice

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.storageservice.room.ExportedDay
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Room(AppDatabase) への「唯一の入口」となる薄いファサード。
 *
 * ■役割
 * - GeoLocationProvider 全体で、Room / DAO を直接触るのはこのオブジェクトだけにする。
 * - Service / ViewModel / Worker / UseCase / UI からは、
 *   LocationSample / ExportedDay に関する操作をすべてここ経由で行う。
 *
 * ■現在の主な利用箇所（2025-11 時点）
 * - ログ記録:
 *     GeoLocationService などから [insertLocation] で 1 件ずつ保存。
 * - 履歴表示:
 *     HistoryViewModel などから [latestFlow] で最新 N 件を監視。
 * - 日次バックアップ:
 *     MidnightExportWorker から
 *       - [getLocationsBetween] / [deleteLocations] で LocationSample を取得・削除
 *       - [ensureExportedDay] / [oldestNotUploadedDay] / [markExportedLocal]
 *         / [markUploaded] / [markExportError] で ExportedDay を管理
 * - 手動エクスポート / Pickup:
 *     DriveSettingsScreen の「今日の Preview」、SelectorUseCases 経由の dataselector から
 *     [getAllLocations] / [getLocationsBetween] を利用。
 *
 * ■方針
 * - ここ以外のモジュールから AppDatabase / DAO を import しないこと。
 * - 並び順・時間区間・例外の扱いなどの「契約」をここで明示し、
 *   呼び出し側はこの契約のみに依存する。
 */
object StorageService {

    // ------------------------------------------------------------------------
    // LocationSample 系 API
    // ------------------------------------------------------------------------

    /**
     * 最新 [limit] 件の LocationSample を「時系列降順」で Flow 監視する。
     *
     * 想定用途
     * - 履歴画面やメイン画面など、「最新のログをリアルタイムに追従したい」場面。
     *
     * 契約
     * - 返ってくるリストは「新しいものが先頭」の順（LocationSampleDao.latestFlow の仕様に依存）。
     * - [limit] は 1 以上である前提（呼び出し側でコントロールすること）。
     * - DB アクセスはバックグラウンドスレッドで処理されるが、
     *   Flow 自体は呼び出し側のコルーチンスコープで collect する。
     */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> =
        AppDatabase.get(ctx).locationSampleDao().latestFlow(limit)

    /**
     * 全 LocationSample を「時系列昇順」(timeMillis の昇順) で取得する。
     *
     * 想定用途
     * - データ件数が比較的少ないことを前提とした「全件エクスポート」系処理。
     *   例: DriveSettingsScreen の「今日の Preview」で、後段で日付フィルタをかける場合など。
     *
     * 契約
     * - 戻り値は timeMillis 昇順。
     * - 件数が多いとメモリ負荷が高くなるため、大量データ用途には [getLocationsBetween] を使うこと。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun getAllLocations(ctx: Context): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao().findAll()
        }

    /**
     * 指定した期間 [from, to) の LocationSample を「時系列昇順」で取得する。
     *
     * 想定用途
     * - MidnightExportWorker のような「日単位」のエクスポート。
     * - Pickup / dataselector など、時間帯を指定した抽出。
     *
     * 契約
     * - 取得する区間は [from, to) の半開区間（to は含まない）。
     * - 戻り値は timeMillis 昇順。
     * - [softLimit] は安全のための上限であり、DAO 側で制御される。
     *   例: レコードが異常に多い日でも、softLimit を超えて取得しない。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun getLocationsBetween(
        ctx: Context,
        from: Long,
        to: Long,
        softLimit: Int = 1_000_000
    ): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao()
                .getInRangeAscOnce(from = from, to = to, softLimit = softLimit)
        }

    /**
     * LocationSample を 1 件挿入し、行 ID を返す。
     *
     * 想定用途
     * - GeoLocationService 等からのリアルタイム位置ログ保存。
     *
     * 契約
     * - 挿入前後の件数と provider / timeMillis を Logcat に出力する（タグ "DB/TRACE"）。
     *   開発・デバッグ時の挙動確認用であり、本番でも許容される程度のログ量を想定。
     * - 例外が発生した場合はそのままスローされる（呼び出し側でハンドリングすること）。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun insertLocation(ctx: Context, sample: LocationSample): Long =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            val before = dao.countAll()
            Log.d(
                "DB/TRACE",
                "count-before=$before provider=${sample.provider} t=${sample.timeMillis}"
            )

            val rowId = dao.insert(sample)

            val after = dao.countAll()
            Log.d("DB/TRACE", "count-after=$after rowId=$rowId")

            rowId
        }

    /**
     * 渡された LocationSample 群をまとめて削除する。
     *
     * 想定用途
     * - MidnightExportWorker での「エクスポート成功時に、その日のレコードを削除する」処理。
     *
     * 契約
     * - [items] が空のときは何もしない（DB アクセスもしない）。
     * - 例外が発生した場合はそのままスローされる。
     *   例: Worker 側などで「削除失敗は致命ではない」と判断する場合は、呼び出し側で try-catch すること。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun deleteLocations(ctx: Context, items: List<LocationSample>) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.deleteAll(items)
        }

    // ------------------------------------------------------------------------
    // ExportedDay 系 API
    // ------------------------------------------------------------------------

    /**
     * 指定された [epochDay] の ExportedDay レコードを ensure する（存在しなければ新規作成）。
     *
     * 想定用途
     * - MidnightExportWorker の初期シード処理。
     *   「過去 N 日分の 'まだ処理されていない可能性がある日' を ExportedDay に用意しておく」ために使う。
     *
     * 契約
     * - epochDay は LocalDate.toEpochDay() と同じ基準（UTC 1970-01-01 からの日数）。
     * - 既に同じ epochDay のレコードがある場合、内容は変更されない。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun ensureExportedDay(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            val dayDao = AppDatabase.get(ctx).exportedDayDao()
            dayDao.ensure(ExportedDay(epochDay = epochDay))
        }

    /**
     * 「まだアップロードが完了していない」中で最も古い ExportedDay を返す。
     *
     * 想定用途
     * - MidnightExportWorker のバックログ処理ループで、
     *   次に処理すべき日付を決定するために使用する。
     *
     * 契約
     * - 対象が存在しない場合は null を返す。
     * - どの状態を「未アップロード」とみなすかは ExportedDayDao 側の実装に依存する。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun oldestNotUploadedDay(ctx: Context): ExportedDay? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().oldestNotUploaded()
        }

    /**
     * 指定された [epochDay] を「ローカル ZIP 出力済み」としてマークする。
     *
     * 想定用途
     * - GeoJSON および ZIP の出力が成功した直後に呼び出す。
     *   アップロードの成否に関わらず、「ローカルには一度出力した」という事実を記録する。
     *
     * 契約
     * - 対象の日付が存在しない場合の挙動は ExportedDayDao の実装に依存する。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun markExportedLocal(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markExportedLocal(epochDay)
        }

    /**
     * 指定された [epochDay] を「アップロード済み」としてマークし、Drive の fileId 等を記録する。
     *
     * 想定用途
     * - Drive へのアップロードが成功したタイミングで呼び出す。
     *
     * 契約
     * - [fileId] は null を許容（API を簡単にするため）。不要な場合は null でもよい。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun markUploaded(ctx: Context, epochDay: Long, fileId: String?) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markUploaded(epochDay, fileId)
        }

    /**
     * 指定された [epochDay] に対して、エラーメッセージ [msg] を記録する。
     *
     * 想定用途
     * - ZIP 生成やアップロードに失敗した際に、その事実を ExportedDay に紐づけておく。
     *   これにより、後から UI やログで「どの日がどんな理由で失敗したか」を追いやすくする。
     *
     * 契約
     * - [msg] は人間が読めるメッセージ想定（HTTP ステータスやレスポンスボディの要約など）。
     * - 呼び出しは必ずサスペンドコンテキストから行うこと。
     */
    suspend fun markExportError(ctx: Context, epochDay: Long, msg: String) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markError(epochDay, msg)
        }
}

