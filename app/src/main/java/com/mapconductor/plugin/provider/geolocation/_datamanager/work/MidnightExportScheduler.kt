package com.mapconductor.plugin.provider.geolocation._datamanager.work

import android.content.Context
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

object MidnightExportScheduler {
    private const val UNIQUE_NAME = "midnight-export-worker"
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun scheduleNext(context: Context) {
        val delayMs = calcDelayUntilNextMidnightMillis()

        val constraints = Constraints.Builder()
            // ローカル ZIP だけでも動かしたいなら CONNECTED 制約は付けない方が安全
            // （アップロードはワーカー内部でネット判定＆再試行）
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    fun ensure(context: Context) = scheduleNext(context)

    private fun calcDelayUntilNextMidnightMillis(): Long {
        val now = ZonedDateTime.now(zone)
        val nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        return Duration.between(now, nextMidnight).toMillis()
    }
}
