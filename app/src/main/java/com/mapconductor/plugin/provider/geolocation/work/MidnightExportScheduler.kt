package com.mapconductor.plugin.provider.geolocation.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 次の 0:00(JST) に 1回だけ実行される OneTimeWork を登録するスケジューラ。
 * - 何度呼んでも UniqueWork で上書き(REPLACE)されるため、二重起動しない。
 * - ネットワーク必須（アップロードがあるため）。
 */
object MidnightExportScheduler {

    private const val UNIQUE_WORK_NAME = "midnight_export"
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun scheduleNext(context: Context) {
        val now: ZonedDateTime = ZonedDateTime.now(zone)
        val nextMidnight: ZonedDateTime = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        val delay: Duration = Duration.between(now, nextMidnight).coerceAtLeast(Duration.ZERO)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
            .setInitialDelay(delay)
            .setConstraints(constraints)
            // 再試行ポリシーは任意。EXponentialの方が一般的。
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )

        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        Log.i(
            LogTags.WORKER,
            "Scheduled MidnightExportWorker at ${nextMidnight.format(fmt)} (delay=${delay.toMinutes()} min)"
        )
    }
}
