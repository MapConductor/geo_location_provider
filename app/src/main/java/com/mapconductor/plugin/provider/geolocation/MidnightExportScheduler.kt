package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object MidnightExportScheduler {

    private const val UNIQUE_NAME = "daily_geojson_backup"

    fun scheduleNext(context: Context) {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = ZonedDateTime.now(zone)
        val nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        val delay = Duration.between(now, nextMidnight).coerceAtLeast(Duration.ofSeconds(10))

        val req = OneTimeWorkRequestBuilder<MidnightExportWorker>()
            // Step.3 のためのバックオフ（失敗時 1分後に自動リトライ）
            .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofMinutes(1))
            .setInitialDelay(delay)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req)
    }
}
