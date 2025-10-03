package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 毎日 0:00 (JST) に MidnightExportWorker を起動するためのスケジューラ。
 *
 * ・UniqueWork 名は MidnightExportWorker.UNIQUE_NAME を使用
 * ・ネットワーク必須（CONNECTED）
 * ・次の 0:00 JST までの delay を計算して OneTimeWork として登録
 * ・アプリ初回起動／アップデート後／端末再起動／設定変更などのタイミングで scheduleNext() を呼び出す想定
 */
object MidnightExportScheduler {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private const val TAG = "MidnightExportScheduler"

    /**
     * 次の 0:00 JST に MidnightExportWorker を一意に予約する。
     * すでに予約があっても KEEP で維持（重複登録しない）。
     */
    fun scheduleNext(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val delayMs = calcDelayUntilNextMidnightMillis()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                MidnightExportWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,   // 既存があれば残す（重複防止）
                req
            )

            Log.i(TAG, "Scheduled next 0:00 JST in ${delayMs}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleNext failed: ${t.message}", t)
        }
    }

    /**
     * 明示的に再登録したい場合（例えばユーザー操作や大きな設定変更時）はこちらを使う。
     * 既存の予約を REPLACE で置き換える。
     */
    fun reschedule(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val delayMs = calcDelayUntilNextMidnightMillis()
            val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                MidnightExportWorker.UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE, // 強制的に置き換え
                req
            )

            Log.i(TAG, "Rescheduled next 0:00 JST in ${delayMs}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "reschedule failed: ${t.message}", t)
        }
    }

    /**
     * 端末再起動やアプリ更新時などの “予約が消えている可能性がある” タイミングで呼ぶ用。
     * 既存があれば KEEP で維持、無ければ新規登録。
     */
    fun ensure(context: Context) {
        scheduleNext(context)
    }

    /** 現在時刻から “次の 0:00 JST” までの遅延ミリ秒を計算する。 */
    private fun calcDelayUntilNextMidnightMillis(): Long {
        val now = ZonedDateTime.now(zone)
        val nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        return Duration.between(now, nextMidnight).toMillis()
    }
}
